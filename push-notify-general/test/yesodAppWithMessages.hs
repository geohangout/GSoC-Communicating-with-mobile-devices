-- Test Example for Push Notifications.
-- This is a simple example of a Yesod server, where devices can register to send/receive
-- messages and users can send messages through the web service.
-- Before running this app, its necessary to complete
-- the "apiKey" and "senderID"    -> in case of using the GCM-CCS service.
-- the certificate and privateKey -> in case of using the APNS service.

{-# LANGUAGE OverloadedStrings, TypeFamilies, TemplateHaskell, FlexibleInstances, ScopedTypeVariables,
             QuasiQuotes, MultiParamTypeClasses, GeneralizedNewtypeDeriving, FlexibleContexts, GADTs #-}

import Yesod
import Database.Persist
import Database.Persist.Sqlite
import Data.Aeson.Types
import Data.Conduit.Pool
import Data.Default
import Data.Functor
import Data.IORef
import Data.Monoid                    ((<>))
import Data.Text.Internal             (empty)
import Data.Text                      (Text,pack,unpack,append,isPrefixOf)
import qualified Control.Exception    as CE
import qualified Data.HashMap.Strict  as HM
import qualified Data.HashSet         as HS
import qualified Data.Text            as T
import qualified Data.Map             as M
import Text.XML
import Text.Hamlet.XML
import Control.Applicative
import Control.Monad                  (mzero)
import Control.Monad.IO.Class         (liftIO)
import Control.Monad.Logger
import Control.Monad.Trans.Resource   (runResourceT)
import Network.HTTP.Conduit
import Network.HTTP.Types.Status
import Network.TLS.Extra              (fileReadCertificate,fileReadPrivateKey)
import Network.PushNotify.Ccs
import Network.PushNotify.Apns
import Network.PushNotify.Mpns
import Network.PushNotify.General
import Extra

-- Data Base:

share [mkPersist sqlSettings, mkMigrate "migrateAll"] [persistLowerCase|
Devices
    user Text
    password Text
    identifier Device
    UniqueUser user
    UniqueDevice identifier
    deriving Show
|]

-- Yesod App:

data Messages = Messages {  connectionPool :: ConnectionPool -- Connection to the Database.
                         ,  pushManager    :: PushManager
                         }

mkYesod "Messages" [parseRoutes|
/ RootR GET
/fromdevices PushManagerR PushManager pushManager
/fromweb FromWebR POST
|]

-- Instances:

instance Yesod Messages

instance YesodPersist Messages where
    type YesodPersistBackend Messages = SqlPersistT
    runDB action = do
        Messages pool _ <- getYesod
        runSqlPool action pool

instance RenderMessage Messages FormMessage where
    renderMessage _ _ = defaultFormMessage

-- Handlers:

-- 'getRootR' provides the main page.
getRootR :: Handler Html
getRootR = do
                his   <- lookupSession "history"
                list  <- runDB $ selectList [] [Desc DevicesUser]
                users <- return $ map (\a -> devicesUser(entityVal a)) list
                defaultLayout $ do
                                setTitle "Just a test example."
                                toWidget [hamlet|
                        $maybe msg <- his
                            <br><b>History:</b><br>
                            $forall message <- T.lines msg
                                <li>#{message}
                            <form method=post action=@{FromWebR}>
                                <input type="hidden" name="clear" value="clear" />
                                <input type=submit style="font-weight:bold;" value="Clear">
                        <br><b>Select a user:</b><br>
                        <form method=post action=@{FromWebR}>
                            $forall user <- users
                                <input type="radio" name="destination" value=#{user}> #{user} <br>
                            <input type="radio" name="destination" value="EveryOne" checked="yes" > Just to every one! <br>
                            <br><b>Write a message:</b><br>
                            <input type="text" name="message" />
                            <input type=submit style="font-weight:bold;" value="Send">
|]

-- 'postFromWebR' receives messages from the website user. (POST messages to '/fromweb')
postFromWebR :: Handler ()
postFromWebR = do
  cl <- runInputPost $ iopt textField "clear"
  case cl of
   Just _ -> do
                 deleteSession "history"
                 redirect RootR
   _      -> do
    msg  <- runInputPost $ ireq textField "message"
    dest <- runInputPost $ ireq textField "destination"
    list <- case dest of
                "EveryOne"  -> runDB $ selectList [] [Desc DevicesIdentifier]
                usr         -> do
                                   res <- runDB $ getBy $ UniqueUser usr
                                   case res of
                                       Just a  -> return [a]
                                       Nothing -> return []
    Messages _ man <- getYesod
    $(logInfo) ("\tA new message: \""<>msg<>"\"\t")
    let regIdsList = map (\a -> devicesIdentifier(entityVal a)) list
    if regIdsList /= []
    then do
            let message = def {
                         gcmNotif  = Just $ def {data_object = Just (HM.fromList [(pack "Message" .= msg)]) }
                     ,   mpnsNotif = Just $ def {target = Toast , restXML = Document (Prologue [] Nothing []) (xmlMessage msg) []}
                     }
            result <- liftIO $ CE.catch -- I catch IO exceptions to avoid showing unsecure information.
                        (sendPush man message (HS.fromList regIdsList))
                        (\(e :: CE.SomeException) -> fail "Problem communicating with Push Servers")
            handleResult message result 5
    else do
            return ()
    his <- lookupSession "history"
    case his of
        Just m  -> setSession "history" (m <> "\n" <> msg)
        Nothing -> setSession "history" msg
    redirect RootR
    where
        xmlMessage msg = Element (Name "Notification" (Just "WPNotification") (Just "wp")) (M.singleton "xmlns:wp" "WPNotification") [xml|
<wp:Toast>
    <wp:Text1>New message:
    <wp:Text2>#{msg}
    <wp:Param>?msg=#{msg}
|]

-- Handle the result of the communication with the Push Servers.
handleResult :: PushNotification -> PushResult -> Int -> Handler ()
handleResult msg res n = do
                           handleFailed         $ failed   res
                           handleToResend msg n $ toResend res

-- Handle the msg that failed, I decide to unregister devices when I get a 400 error trying to send them a notification.
handleFailed :: HM.HashMap Device (Either Text CE.SomeException) -> Handler ()
handleFailed list = let l = HM.keys $ HM.filter is400 list
                    in if l /= []
                         then do
                                  let removeUnRegUser regid = runDB $ deleteBy $ UniqueDevice regid
                                  mapM_ removeUnRegUser l
                         else return ()
                    where
                        is400 (Left _)  = False
                        is400 (Right e) = case (CE.fromException e) :: Maybe HttpException of
                                              Just (StatusCodeException status _ _) -> (statusCode status) == 400
                                              _                                     -> False

-- Handle the regIds that I need to resend because of an internal server error. (Unavailable error)
handleToResend :: PushNotification -> Int -> HS.HashSet Device -> Handler ()
handleToResend msg 0 set = return ()
handleToResend msg n set = do
                    $(logInfo) ("\tHandling ToReSend: "<>pack (show set)<>"\t")
                    if HS.null set
                    then return ()
                    else do
                        Messages _ m <- getYesod
                        res <- liftIO $ CE.catch -- I catch IO exceptions to avoid showing unsecure information.
                                    (sendPush m msg set)
                                    (\(e :: CE.SomeException) -> fail "Problem communicating with Push Servers")
                        handleResult msg res (n-1)

main :: IO ()
main = do
  runResourceT . runNoLoggingT $ withSqlitePool "DevicesDateBase.db3" 10 $ \pool -> do
    runSqlPool (runMigration migrateAll) pool
    liftIO $ do
      ref <- newIORef Nothing
--    cert <- fileReadCertificate "public-cert.pem" -- your APNS Certificate
--    key  <- fileReadPrivateKey  "private-key.pem" -- your APNS PrivateKey
      man  <- startPushService $ PushServiceConfig{
            pushConfig           = def{
                                       gcmConfig  = Just $ Ccs $ 
                                                      def{ aPiKey   = ""  -- Here you must complete with the
                                                         , senderID = ""} -- correct Api Key and SenderId.
--                                 ,   apnsConfig = Just $ def{environment = Local , apnsCertificate  = cert , apnsPrivateKey = key }
                                   ,   mpnsConfig = Just def
                                   }
        ,   newMessageCallback   = handleNewMessage pool ref
        ,   newDeviceCallback    = handleNewDevice pool
        ,   unRegisteredCallback = handleUnregistered pool
        ,   newIdCallback        = handleNewId pool
        }
      writeIORef ref $ Just man
      warp 3000 $ Messages pool man

      where
       pars :: Value -> Parser (Text,Text)
       pars (Object v) = (,) <$>
                 v .: "user" <*>
                 v .: "password"
       pars _          = mzero

       parsMsg :: Value -> Parser Text
       parsMsg (Object v) = v .: "message"
       parsMsg _          = mzero

       runDBAct p a = runResourceT . runNoLoggingT $ runSqlPool a p

       handleNewDevice pool d v = do
          res  <- return $ parseMaybe pars v
          case res of
            Nothing         -> return $ ErrorReg "No User or Password"
            Just (usr,pass) -> do
              putStr ("\nIntent for a new user!:\n-User: " ++ show usr ++ "\n-Password: "
                      ++ show pass ++ "\n-Identifier: " ++ show d ++ "\n")
              device <- runDBAct pool $ getBy $ UniqueUser usr
              case device of
                Nothing -> do
                             dev <- runDBAct pool $ getBy $ UniqueDevice d
                             case dev of
                               Just x  -> do
                                            runDBAct pool $ update (entityKey (x)) [DevicesUser =. usr , DevicesPassword =. pass]
                                            return SuccessfulReg
                               Nothing -> do
                                            runDBAct pool $ insert $ Devices usr pass d
                                            return SuccessfulReg
                Just a	-> case devicesPassword (entityVal (a)) == pass of
                             True  -> do
                                        runDBAct pool $ update (entityKey (a)) [DevicesIdentifier =. d]
                                        return SuccessfulReg
                             False -> return $ ErrorReg "Invalid Username or Password"

       handleNewMessage pool ref d v = do
          Just man <- readIORef ref
          res  <- return $ parseMaybe pars v
          case res of
            Nothing         -> return ()
            Just (usr,pass) -> do
              device <- runDBAct pool $ getBy $ UniqueUser usr
              case device of
                Nothing -> return ()
                Just a	-> case devicesPassword (entityVal (a)) == pass of
                             False -> return ()
                             True  -> do
                                        m  <- return $ parseMaybe parsMsg v
                                        case m of
                                          Nothing  -> return ()
                                          Just msg -> do
                                                        let message = def {gcmNotif  = Just $ def {
                                                                      data_object = Just (HM.fromList [(pack "Message" .= msg)]) }}
                                                        sendPush man message $ HS.singleton d
                                                        putStr ("\nNew message from device!:\n-User: " ++ show usr
                                                                ++ "\n-Msg: " ++ show msg ++ "\n")

       handleNewId pool (old,new) = do
                                      dev  <- runDBAct pool $ getBy $ UniqueDevice old
                                      case dev of
                                          Just x  ->  runDBAct pool $ update (entityKey (x)) [DevicesIdentifier =. new ]
                                          Nothing ->  return ()

       handleUnregistered pool d = runDBAct pool $ deleteBy $ UniqueDevice d
