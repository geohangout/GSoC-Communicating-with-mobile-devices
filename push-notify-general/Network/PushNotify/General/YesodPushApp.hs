{-# LANGUAGE OverloadedStrings, TypeFamilies, TemplateHaskell, FlexibleInstances, MultiParamTypeClasses, FlexibleContexts, QuasiQuotes #-}
-- | This Module defines the Yesod subsite to be used for the registration and reception of messages from devices.
module Network.PushNotify.General.YesodPushApp(
  PushManager(..)
  ) where

import Network.PushNotify.General.Types
import Network.PushNotify.General.YesodPushAppRoutes
import Yesod
import Control.Concurrent
import Data.Text
import Data.Aeson
import qualified Data.HashMap.Strict as HM

instance (RenderMessage master FormMessage, Yesod master) => YesodSubDispatch PushManager (HandlerT master IO) where
    yesodSubDispatch = $(mkYesodSubDispatch resourcesPushManager)

-- 'postRegister' allows a mobile device to register. (JSON POST messages to '/register')
postSubRegisterR :: (RenderMessage master FormMessage, Yesod master) => HandlerT PushManager (HandlerT master IO) ()
postSubRegisterR = do
    value  <- parseJsonBody_
    case value of
        Object v -> do
                      iden <- lookForIdentifier v
                      pushManager <- getYesod
                      res <- liftIO $ (newDeviceCallback $ serviceConfig pushManager) iden value
                      case res of
                        SuccessfulReg -> sendResponse $ RepJson emptyContent -- successful registration.
                        ErrorReg t    -> permissionDenied t                  -- error in registration.
        _        -> invalidArgs []

lookForIdentifier :: Object -> HandlerT PushManager (HandlerT master IO) Device
lookForIdentifier v = do
                    regId <- case (HM.lookup "regId" v) of
                                 Just (String s) -> return s
                                 _               -> invalidArgs []
                    case (HM.lookup "system" v) of
                      Just (String "ANDROID") -> return $ GCM  regId -- A Android device.
                      Just (String "WPHONE")  -> return $ MPNS regId -- A WPhone device.
                      Just (String "IOS")     -> return $ APNS regId -- A iOS device.
                      _                       -> invalidArgs []

-- 'postMessages' allows a mobile device to send a message. (JSON POST messages to '/messages')
postSubMessagesR :: (RenderMessage master FormMessage, Yesod master) => HandlerT PushManager (HandlerT master IO) ()
postSubMessagesR = do
    value  <- parseJsonBody_
    case value of
        Object v -> do
                      iden <- lookForIdentifier v
                      pushManager <- getYesod
                      liftIO $ forkIO $ (newMessageCallback $ serviceConfig pushManager) iden value
                      sendResponse $ RepJson emptyContent
        _        -> invalidArgs []
