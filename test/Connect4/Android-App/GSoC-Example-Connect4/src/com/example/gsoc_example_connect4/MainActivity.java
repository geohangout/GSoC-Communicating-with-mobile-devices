package com.example.gsoc_example_connect4;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.support.v4.app.FragmentActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.gsoc_example_connect4.R;

import static com.example.gsoc_example_connect4.CommonUtilities.EXTRA_CANCEL;
import static com.example.gsoc_example_connect4.CommonUtilities.EXTRA_MOVEMENT;
import static com.example.gsoc_example_connect4.CommonUtilities.EXTRA_ERRORSENDING;
import static com.example.gsoc_example_connect4.CommonUtilities.NEW_MESSAGE_ACTION;

public class MainActivity extends FragmentActivity {

	public static final String EXTRA_MESSAGE = "message";
    public static final String PROPERTY_REG_ID = "registration_id";
    public static final String PROPERTY_ON_SERVER_EXPIRATION_TIME = "onServerExpirationTimeMs";
    AtomicInteger msgId = new AtomicInteger();
    
    // Default lifespan (7 days) of a reservation until it is considered expired.
    public static final long REGISTRATION_EXPIRY_TIME_MS = 1000 * 3600 * 24 * 7;

    // Tag used on log messages.
    static final String TAG = "GSoC-Example";
    
    SharedPreferences prefs;
    Context context;

    String regid,user,password;
    String actualPlayer = null;
    Boolean registered;
    AlertDialog dialog;
    MainActivity local;
    Board board;
    List<String> listUsers = null;
    GridView gridviewBack;
    
    Boolean onForeGround=true;
    
    private View mConnectingStatusView;
    private View mMainPageView;
    private View mInitPageView;
		
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
           
        registerReceiver(mHandleMessageReceiver,
                new IntentFilter(NEW_MESSAGE_ACTION));
        
        prefs = getSharedPreferences(MainActivity.class.getSimpleName(),Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        onForeGround=true;
		editor.putBoolean("onForeGround", true);
		editor.commit();
        registered = prefs.getBoolean("registered", false);
        user = prefs.getString("user","");
        password = prefs.getString("password","");
        context = getApplicationContext();
        local = MainActivity.this;
        regid = getRegistrationId(context);
        actualPlayer = prefs.getString("actualPlayer",null);
        
        gridviewBack = (GridView) findViewById(R.id.gridviewBack);
        mMainPageView = findViewById(R.id.mainPage);
        mInitPageView = findViewById(R.id.initPage);
        mConnectingStatusView = findViewById(R.id.connecting_status);
        
    	findViewById(R.id.finish_button).setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View view) {
						sendCancel();		
					}
				});
        findViewById(R.id.start_button).setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View view) {
				    	choosePlayer();		
					}
				});
        
        if(!registered || regid == ""){
            Intent intent = new Intent(context, Register.class);
            startActivityForResult(intent,1000);
        }
        else{
        	String winner;
        	if(prefs.getString("newInvitation", null) != null){
        		NewInvitation n = new NewInvitation();
        		n.show(getSupportFragmentManager(), "NewInvitation");
        		editor.remove("newInvitation");
        		editor.commit();
            }
        	else if((winner = prefs.getString("newWinner", null)) != null){
        		AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage(winner + " is the winner!")
                	   .setNeutralButton(R.string.close, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                		Intent intentNew = getIntent();
                    	finish();
                    	startActivity(intentNew);
                    }
                });
                dialog = builder.create();
                dialog.show();
        		editor.remove("newWinner");
        		editor.commit();
            }
        	showGame(false,false);
        }
        
    }
        
    void showGame(Boolean b1, Boolean b2){
    	     
    	if(actualPlayer == null)
    		showBoard(false);
        else{
	    	board = new Board(prefs,b1,b2);
	        
	        int displayWidth  = getResources().getDisplayMetrics().widthPixels ;
	        int displayHeight = getResources().getDisplayMetrics().heightPixels ;
	        int size,pad;
	        
	        if(displayWidth > displayHeight)
	        	size = (displayHeight / 7);
	        else
	        	size = (displayWidth / 7);
	       	pad = (displayWidth - (size*7)) /2;
	       	
	        gridviewBack.setAdapter(new ImageAdapterBack(this,board,size));
	        gridviewBack.setColumnWidth(size);
	        gridviewBack.setOnItemClickListener(new AdapterView.OnItemClickListener() {
	            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
	            	local.newMovement(position%7);
	            }
	        });
	        gridviewBack.setPadding(pad,0, pad , 0);
	        
	        TextView mainTextView = (TextView) findViewById(R.id.mainText);
	    	mainTextView.setText("Playing against: " + actualPlayer + "  ");
	        showBoard(true);
        }
    }  
    
    public String getActualPlayer(){
    	return actualPlayer;
    }
    
    void choosePlayer(){
    	showProgress(true);
    	new AsyncTask<Void, Void, List<String>>() {

    		@Override
    		protected List<String> doInBackground(Void... parameters) {
    			return ServerUtilities.getUsersList(user);
    		}
    		protected void onPostExecute(List<String> result) {
    			if (result != null)
    				showProgress(false);
    			continue1(result);
    	    }
    	}.execute(null,null,null);
    }
    
    public List<String> getListUsers(){
    	return listUsers;
    }
    
    void continue1(List<String> list){
    	listUsers = list;
    	SelectUser s = new SelectUser();
        s.show(getSupportFragmentManager(), "SelectUser");
    }
    
    void resultChoose(int pos){
    	if(pos >= 0){
    		actualPlayer = listUsers.get(pos);
    		TextView mainTextView = (TextView) findViewById(R.id.mainText);
    		mainTextView.setText("Playing against: " + actualPlayer + "  ");
    		showGame(true,false);
    		SharedPreferences.Editor editor = prefs.edit();
    		editor.putString("actualPlayer", actualPlayer);
    		editor.commit();
    		sendNewGame();
    	}
    	else
    		showProgress(false);
    }
    
    void finishGame(){
    	actualPlayer = null;
    	SharedPreferences.Editor editor = prefs.edit();
    	editor.remove("actualPlayer");
		editor.remove("board");
		editor.remove("turn");
		editor.commit();
    	gridviewBack.invalidateViews();
    	showBoard(false);
    }
    
    public void newMovement(int position){
    	if(board.getTurn() == 1)
    		showToast("Your turn");
    	else
    		showToast(actualPlayer + " plays");
    	if(board.newMovement(position,1)){
    		gridviewBack.invalidateViews();
    		sendMovement(position);
    	}
    }
    
    void showToast(CharSequence text){
    	int duration = Toast.LENGTH_SHORT;
    	Toast toast = Toast.makeText(context, text, duration);
    	toast.show();
    }
    
    @Override
    protected void onDestroy() {
        unregisterReceiver(mHandleMessageReceiver);
        super.onDestroy();
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu, this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            //Show some info about this example.
            case R.id.options_information:
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage(R.string.info_message)
                       .setTitle(R.string.info_title)
                	   .setNeutralButton(R.string.close, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        ;
                    }
                });
                dialog = builder.create();
                dialog.show();
                return true;
                
            case R.id.options_settings:
            	Intent intent = new Intent(this, SettingsActivity.class);
				startActivity(intent);
				return true;
            //Close the app.
            case R.id.options_exit:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	super.onActivityResult(requestCode, resultCode, data);
    	//Returning from the register activity.
    	if(requestCode == 1000)
    		if(resultCode == RESULT_OK){
    			user = data.getStringExtra("USER");
    			password = data.getStringExtra("PASSWORD");
    			regid = data.getStringExtra("REGID");
    			SharedPreferences.Editor editor = prefs.edit();
    			editor.putString("user", user);
    			editor.putString("password", password);
    			editor.putString("user", user);
    			editor.putString("password", password);
    			registered = prefs.getBoolean("registered", false);
    			// Commit the edits!
    			editor.commit();
    			showGame(false,false);
    		}
    }
        
    
    // Gets the current registration id for application on GCM service.
    // If result is empty, the registration has failed.
    private String getRegistrationId(Context context) {
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId.length() == 0) {
            Log.v(TAG, "Registration not found.");
            return "";
        }
        // check if registration expired.
        if (isRegistrationExpired()) {
            Log.v(TAG, "registration expired.");
            return "";
        }
        return registrationId;
    }
    
    // Checks if the registration has expired.
    // To avoid the scenario where the device sends the registration to the
    // server but the server loses it, the app developer may choose to re-register
    // after REGISTRATION_EXPIRY_TIME_MS.
    // return true if the registration has expired.
    private boolean isRegistrationExpired() {
        // checks if the information is not stale
        long expirationTime = prefs.getLong(PROPERTY_ON_SERVER_EXPIRATION_TIME, -1);
        return System.currentTimeMillis() > expirationTime;
    }
    
    
    // To Receive the messages to be shown in the text view.
    private final BroadcastReceiver mHandleMessageReceiver =
            new BroadcastReceiver() {
        
        @Override
        public void onReceive(final Context context, Intent intent) {
        	if( intent.getExtras().getString(EXTRA_CANCEL) != null){
        		AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setMessage(actualPlayer + " has cancelled the game!")
                	   .setNeutralButton(R.string.close, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                		Intent intentNew = getIntent();
                    	finish();
                    	startActivity(intentNew);
                    }
                });
                dialog = builder.create();
                dialog.show();
        	}
        	else{
        		if(onForeGround){
        			Intent intentNew = getIntent();
        			finish();
        			startActivity(intentNew);
        		}else
        			finish();
        	}
        }
    };
    
    public void sendCancel() {
    	context = this;
    	if(registered){
    		showToast("Sending info to server.");
    		new AsyncTask<String, Void, Boolean>() {
    			@Override
    			protected Boolean doInBackground(String... parameters) {
    				return ServerUtilities.sendMsgToServer(context,regid,user,password,parameters[0],parameters[1],msgId); 
    			}
    			protected void onPostExecute(Boolean result) {
        			if (!result){
        				showToast("Couldn't send info to sever.");
        			}
        			else{
        				finishGame();
        				showToast("Cancelled");
        		    }
        	    }
    		}.execute("Cancel","",null);
        }
    	else 
    		showToast("You have to be registered to play.");
    }
    
    public void sendNewGame() {
    	context = this;
    	if(registered){
    		showToast("Sending info to server.");
    		new AsyncTask<String, Void, Boolean>() {
    			@Override
    			protected Boolean doInBackground(String... parameters) {
    				return ServerUtilities.sendMsgToServer(context,regid,user,password,parameters[0],parameters[1],msgId); 
    			}
    			protected void onPostExecute(Boolean result) {
        			if (!result){
        				finishGame();
        				showToast("Couldn't send info to sever.");
        			}
        			else{
        				showToast("Sent");
        		    }
        	    }
    		}.execute("NewGame",actualPlayer,null);
        }
    	else 
    		showToast("You have to be registered to play.");
    }
    
    public void sendMovement(int position) {
    	context = this;
    	if(registered){
    		showToast("Sending info to server.");
    		new AsyncTask<Integer, Void, Integer>() {
    			@Override
    			protected Integer doInBackground(Integer... parameters) {
    				if(ServerUtilities.sendMsgToServer(context,regid,user,password,"Movement",String.valueOf(parameters[0]),msgId))
    					return parameters[0];
    				else
    					return -1;
    			}
    			protected void onPostExecute(Integer result) {
        			if (result == -1){
        	    		board.cancelMovement(result);
        	    		gridviewBack.invalidateViews();
        	    		showToast("Couldn't send info to sever.");
        			}
        			else{
        				showToast("Sent");
        		    }
        	    }
    		}.execute(position,null,null);
        }
    	else 
    		showToast("You have to be registered to play.");
    }
    
    private void showBoard(final boolean show) {
    	mMainPageView.setVisibility(show ? View.VISIBLE : View.GONE);
    	mInitPageView.setVisibility(show ? View.GONE : View.VISIBLE);
    }  
    
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
	private void showProgress(final boolean show) {
			mConnectingStatusView.setVisibility(show ? View.VISIBLE : View.GONE);
			mInitPageView.setVisibility(show ? View.GONE : View.VISIBLE);
			if(show)
				mMainPageView.setVisibility( View.GONE );
	}
    
    @Override
    public void onPause() {
    	super.onPause();
    	SharedPreferences.Editor editor = prefs.edit();
    	onForeGround=false;
    	editor.putBoolean("onForeGround", false);
		editor.commit();
    }
    @Override
    public void onResume() {
    	super.onResume();
    	SharedPreferences.Editor editor = prefs.edit();
    	onForeGround=true;
		editor.putBoolean("onForeGround", true);
		editor.commit();
    }    
}
