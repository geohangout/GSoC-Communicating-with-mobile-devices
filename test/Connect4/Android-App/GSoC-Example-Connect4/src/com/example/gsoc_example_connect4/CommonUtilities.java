package com.example.gsoc_example_connect4;

//This class provide some common methods and constants.
public final class CommonUtilities {
	
	//The server url, the device will send its regId and user information to this.
	static final String SERVER_URL = "";
	
	//Google API project id registered.
    static final String SENDER_ID = "";// You must complete with Project ID

    //Intent used for showing messages on the screen.
    static final String NEW_MESSAGE_ACTION =
            "com.example.gsoc_example_connect4.NEW_MESSAGE";
    
    //Intent's extra which contains the new movement.
    static final String EXTRA_MOVEMENT = "Movement";
    
    static final String EXTRA_ERRORSENDING = "ErrorSending";
    
    static final String EXTRA_NEWGAME = "NewGame";
    
    static final String EXTRA_WINNER = "Winner";
    
    static final String EXTRA_CANCEL = "Cancel";
    
}
