package org.storymaker.app;

import org.apache.commons.io.FilenameUtils;
import org.storymaker.app.media.MediaProjectManager;
import org.storymaker.app.model.Lesson;
import org.storymaker.app.model.LessonGroup;
import org.storymaker.app.model.Project;
import org.storymaker.app.server.LoginActivity;
import org.storymaker.app.server.ServerManager;
import org.storymaker.app.ui.MyCard;
import info.guardianproject.onionkit.ui.OrbotHelper;
import scal.io.liger.Constants;
import scal.io.liger.DownloadHelper;
import scal.io.liger.IndexManager;
import scal.io.liger.JsonHelper;
import scal.io.liger.MainActivity;
import scal.io.liger.QueueManager;
import scal.io.liger.StorageHelper;
import scal.io.liger.ZipHelper;
import scal.io.liger.model.BaseIndexItem;
import scal.io.liger.model.ContentPackMetadata;
import scal.io.liger.model.ExpansionIndexItem;
import scal.io.liger.model.InstanceIndexItem;
import scal.io.liger.model.StoryPath;
import scal.io.liger.model.StoryPathLibrary;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.hockeyapp.android.CrashManager;
import net.hockeyapp.android.CrashManagerListener;
import net.hockeyapp.android.UpdateManager;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.fima.cardsui.views.CardUI;
//import com.google.analytics.tracking.android.GoogleAnalytics;
import com.viewpagerindicator.CirclePageIndicator;

public class HomeActivity extends BaseActivity {
    private final static String TAG = "HomeActivity";

    private ProgressDialog mLoading;
    private ArrayList<Project> mListProjects;
    private RecyclerView mRecyclerView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    // private DownloadPoller downloadPoller = null;

    private boolean loggedIn;

    // added for testing
    public void scroll(int position) {
        Log.d("TEST", "Scrolling to index item " + position);
        mRecyclerView.scrollToPosition(position);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // file cleanup
        File actualStorageDirectory = StorageHelper.getActualStorageDirectory(this);

        if (actualStorageDirectory != null) {
            JsonHelper.cleanup(actualStorageDirectory.getPath());
        } else {
            // this is an error, will deal with it below
        }

        // default
        loggedIn = false;

        // set title bar as a reminder if test server is specified
        getActionBar().setTitle(Utils.getAppName(this));

        if (actualStorageDirectory != null) {
            // copy index files
            IndexManager.copyAvailableIndex(this, false); // TODO: REPLACE THIS WITH INDEX DOWNLOAD (IF LOGGED IN) <- NEED TO COPY FILE FOR BASELINE CONTENT

            // NEW/TEMP
            // DOWNLOAD AVAILABE INDEX FOR CURRENT USER AND SAVE TO TARGET FILE
            // NEED TO ACCOUNT FOR POSSIBLE MISSING INDEX
            IndexTask iTask = new IndexTask(this, true); // force download at startup (maybe only force on a timetable?)
            iTask.execute();
        } else {
            //show storage error message
            new AlertDialog.Builder(this)
                    .setTitle(Utils.getAppName(this))
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setMessage(R.string.err_storage_not_available)
                    .show();
        }

        // we want to grab required updates without restarting the app
        // integrate with index task
        // if (!DownloadHelper.checkAndDownload(this)) {
        //     Toast.makeText(this, "Downloading content and/or updating installed files", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
        // }

        // i don't think we ever want to do this
        // IndexManager.copyInstalledIndex(this);

        setContentView(R.layout.activity_home);
        mRecyclerView = (RecyclerView) findViewById(scal.io.liger.R.id.recyclerView);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        mSwipeRefreshLayout = (SwipeRefreshLayout) findViewById(R.id.swipeRefreshLayout);
        mSwipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                IndexTask iTask = new IndexTask(HomeActivity.this, true); // force download on manual refresh
                iTask.execute();
            }
        });
        
        // action bar stuff
        getActionBar().setDisplayHomeAsUpEnabled(true);
        
        checkForTor();

        checkForUpdates();
        
    }

    private class IndexTask extends AsyncTask<Void, Void, Boolean> {

        private Context mContext;
        private boolean forceDownload;

        public IndexTask(Context context, boolean forceDownload) {
            this.mContext = context;
            this.forceDownload = forceDownload;
        }

        @Override
        protected Boolean doInBackground(Void... params) {

            Log.d(TAG, "IndexTask.doInBackground IS RUNNING");

            boolean loginRequest = false;

            ServerManager sm = StoryMakerApp.getServerManager();

            if (sm.hasCreds()) {
                // user is logged in, update status flag if necessary
                if (!loggedIn) {
                    loggedIn = true;
                    loginRequest = true; // user just logged in, need to check server
                }
            } else {
                // user is not logged in, update status flag if necessary
                if (loggedIn) {
                    loggedIn = false;
                }
            }

            // check server if user just logged in
            if (loginRequest) {
                Log.d(TAG, "USER LOGGED IN, CHECK SERVER");

                // reset available index
                IndexManager.copyAvailableIndex(mContext, false);

                // attempt to download new assignments
                return Boolean.valueOf(sm.index());
            }

            // check server if user insists
            if (forceDownload) {
                Log.d(TAG, "UPDATE REQUIRED, CHECK SERVER");

                // reset available index
                IndexManager.copyAvailableIndex(mContext, false);

                // attempt to download new assignments
                return Boolean.valueOf(sm.index());
            }

            // no-op
            return false;
        }

        protected void onPostExecute(Boolean result) {
            if (result.booleanValue()) {
                Log.d(TAG, "DOWNLOADED ASSIGNMENTS AND UPDATED AVAILABLE INDEX");
            } else {
                Log.d(TAG, "DID NOT DOWNLOAD ASSIGNMENTS OR UPDATE AVAILABLE INDEX");
            }

            mSwipeRefreshLayout.setRefreshing(false);
            // resolve available/installed conflicts and grab updates if needed
            if (!DownloadHelper.checkAndDownload(mContext)) {
                Toast.makeText(mContext, "Downloading content and/or updating installed files", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
            }
            // refresh regardless (called from onResume and OnRefreshListener)
            initActivityList();
        }
    }
    
    @Override
	public void onResume() {
		super.onResume();

        getActionBar().setTitle(Utils.getAppName(this));

        checkForCrashes();

        //if (!DownloadHelper.checkAllFiles(this) && downloadPoller == null) {
        // integrate with index task
        //if (!DownloadHelper.checkAndDownload(this)) {
            // don't poll, just pop up message if a download was initiated
            //downloadPoller = new DownloadPoller();
            //downloadPoller.execute("foo");
        //    Toast.makeText(this, "Downloading content and/or updating installed files", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
        //} //else {
        // merge this with index task
         //   initActivityList();

        // need to check this to determine whether there is a storage issue that will cause a crash
        File actualStorageDirectory = StorageHelper.getActualStorageDirectory(this);

        if (actualStorageDirectory != null) {
            IndexTask iTask = new IndexTask(this, false); // don't force download on resume (currently triggers only on login)
            iTask.execute();
        } else {
            //show storage error message
            new AlertDialog.Builder(this)
                    .setTitle(Utils.getAppName(this))
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setMessage(R.string.err_storage_not_available)
                    .show();
        }

        //}
		
		boolean isExternalStorageReady = Utils.Files.isExternalStorageReady();
		
		if (!isExternalStorageReady)
		{
			//show storage error message
			new AlertDialog.Builder(this)
            .setTitle(Utils.getAppName(this))
            .setIcon(android.R.drawable.ic_dialog_info)
            .setMessage(R.string.err_storage_not_ready)
            .show();
			
		}
	}

    public static String parseInstanceDate(String filename) {
//        String jsonFilePath = storyPath.buildTargetPath(storyPath.getId() + "-instance-" + timeStamp.getTime() + ".json");
        String[] splits = FilenameUtils.removeExtension(filename).split("-");
        return splits[splits.length-1]; // FIXME make more robust and move into liger
    }

    // copied this as a short term fix until we get loading cleanly split out from the liger sample app ui stuff
    private StoryPathLibrary initSPLFromJson(String json, String jsonPath) {
        if (json == null || json.equals("")) {
            Toast.makeText(this, "Was not able to load this path, content is missing!", Toast.LENGTH_LONG).show();
            finish();
            return null;
        }

        ArrayList<String> referencedFiles = null;

        // should not need to insert dependencies into a saved instance
        if (jsonPath.contains("instance")) {
            referencedFiles = new ArrayList<String>();
        } else {
            referencedFiles = JsonHelper.getInstancePaths(this);
        }

        StoryPathLibrary storyPathLibrary = JsonHelper.deserializeStoryPathLibrary(json, jsonPath, referencedFiles, this, StoryMakerApp.getCurrentLocale().getLanguage());

        if ((storyPathLibrary != null) && (storyPathLibrary.getCurrentStoryPathFile() != null)) {
            storyPathLibrary.loadStoryPathTemplate("CURRENT", false);
        }

        return storyPathLibrary;
    }

    private void initActivityList () {
        // menu items now locked during downloads, i think this can be removed
        /*
        if (!DownloadHelper.checkAllFiles(this)) { // FIXME the app should define these, not the library
            Toast.makeText(this, "Please wait for the content pack to finish downloading and reload the app", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
            return;
        }
        */

        JsonHelper.setupFileStructure(this);

        // NEW: load instance index
        String lang = StoryMakerApp.getCurrentLocale().getLanguage();
        Log.d(TAG, "lang returned from getCurrentLocale: " + lang);
        HashMap<String, InstanceIndexItem> instanceIndex = IndexManager.fillInstanceIndex(HomeActivity.this, IndexManager.loadInstanceIndex(HomeActivity.this),lang);

        // TEMP
        if (instanceIndex.size() > 0) {
            Log.d(TAG, "INITACTIVITYLIST - FOUND INSTANCE INDEX WITH " + instanceIndex.size() + " ITEMS");
        } else {
            Log.d(TAG, "INITACTIVITYLIST - FOUND INSTANCE INDEX WITH NO ITEMS");
        }

        ArrayList<BaseIndexItem> instances = new ArrayList<BaseIndexItem>(instanceIndex.values());

        HashMap<String, ExpansionIndexItem> availableIds = IndexManager.loadAvailableIdIndex(this);
        HashMap<String, ExpansionIndexItem> installedIds = IndexManager.loadInstalledIdIndex(this);

        for (String id : availableIds.keySet()) {
            if (installedIds.keySet().contains(id)) {
                // if the available item has been installed, add the corresponding item from the installed index
                instances.add(installedIds.get(id));
            } else {
                // if the available item has not been installed, add the item from the available index
                instances.add(availableIds.get(id));
            }
        }

        Collections.sort(instances, Collections.reverseOrder()); // FIXME we should sort this down a layer, perhaps in loadInstanceIndexAsList

        mRecyclerView.setAdapter(new InstanceIndexItemAdapter(instances, new InstanceIndexItemAdapter.BaseIndexItemSelectedListener() {
            @Override
            public void onStorySelected(BaseIndexItem selectedItem) {

                if (selectedItem instanceof InstanceIndexItem) {
                    launchLiger(HomeActivity.this, null, ((InstanceIndexItem) selectedItem).getInstanceFilePath(), null);
                } else {

                    // get clicked item
                    ExpansionIndexItem eItem = ((ExpansionIndexItem)selectedItem);

                    // get installed items
                    HashMap<String, ExpansionIndexItem> installedIds = IndexManager.loadInstalledIdIndex(HomeActivity.this);

                    // initiate check/download whether installed or not
                    boolean readyToOpen = DownloadHelper.checkAndDownload(HomeActivity.this, eItem); // <- THIS SHOULD PICK UP EXISTING PARTIAL FILES
                                                                                                     // <- THIS ALSO NEEDS TO NOT INTERACT WITH THE INDEX
                                                                                                     // <- METADATA UPDATE SHOULD HAPPEN WHEN APP IS INITIALIZED

                    if (!installedIds.containsKey(eItem.getExpansionId())) {

                        // if clicked item is not installed, update index
                        IndexManager.registerInstalledIndexItem(HomeActivity.this, eItem);

                        Log.d("HOME MENU CLICK", eItem.getExpansionId() + " NOT INSTALLED, ADDING ITEM TO INDEX");

                        // wait for index serialization
                        try {
                            synchronized (this) {
                                wait(1000);
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {

                        Log.d("HOME MENU CLICK", eItem.getExpansionId() + " INSTALLED, CHECKING FILE");

                        // if clicked item is installed, check state
                        if (readyToOpen) {

                            // if file has been downloaded, open file
                            Log.d("HOME MENU CLICK", eItem.getExpansionId() + " INSTALLED, FILE OK");

                            // update with new thumbnail path
                            // move this somewhere that it can be triggered by completed download?
                            ContentPackMetadata metadata = IndexManager.loadContentMetadata(HomeActivity.this,
                                    eItem.getPackageName(),
                                    eItem.getExpansionId(),
                                    StoryMakerApp.getCurrentLocale().getLanguage());

                            if ((eItem.getThumbnailPath() == null) || (!eItem.getThumbnailPath().equals(metadata.getContentPackThumbnailPath()))) {

                                Log.d("HOME MENU CLICK", eItem.getExpansionId() + " FIRST OPEN, UPDATING THUMBNAIL PATH");

                                eItem.setThumbnailPath(metadata.getContentPackThumbnailPath());
                                IndexManager.registerInstalledIndexItem(HomeActivity.this, eItem);

                                // wait for index serialization
                                try {
                                    synchronized (this) {
                                        wait(1000);
                                    }
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            ArrayList<InstanceIndexItem> contentIndex = IndexManager.loadContentIndexAsList(HomeActivity.this,
                                    eItem.getPackageName(),
                                    eItem.getExpansionId(),
                                    StoryMakerApp.getCurrentLocale().getLanguage());
                            if (contentIndex.size() == 1) {
                                launchLiger(HomeActivity.this, null, null, contentIndex.get(0).getInstanceFilePath());
                            } else {
                                String[] names = new String[contentIndex.size()];
                                String[] paths = new String[contentIndex.size()];
                                int i = 0;
                                for (InstanceIndexItem item : contentIndex) {
                                    names[i] = item.getTitle();
                                    paths[i] = item.getInstanceFilePath();
                                    i++;
                                }
                                showSPLSelectorPopup(names, paths);
                            }
                        } else {
                            // if file is being downloaded, don't open
                            Log.d("HOME MENU CLICK", eItem.getExpansionId() + " INSTALLED, CURRENTLY DOWNLOADING FILE");

                            Toast.makeText(HomeActivity.this, "Please wait for this content pack to finish downloading", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
                        }
                    }
                }
            }
        }));
    }

//
//
//    private String buildZipFilePath(String filePath)
//    {
//        //create datestamp
//        Date date = new Date();
//        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy");
//
//        int index = filePath.lastIndexOf('/');
//        filePath = filePath.substring(0, index + 1);
//
//        return String.format("%sstorymaker_project_%s_%s.zip", filePath, mMPM.mProject.getId(), dateFormat.format(date));
//    }
//
//    private void exportProjectFiles()
//    {
//        try
//        {
//            File fileProjectSrc = MediaProjectManager.getExternalProjectFolder(mMPM.mProject, mMPM.getContext());
//            ArrayList<File> fileList= new ArrayList<File>();
//            String mZipFileName = buildZipFilePath(fileProjectSrc.getAbsolutePath());
//
//            //if not enough space
//            if(!mMPM.checkStorageSpace())
//            {
//                return;
//            }
//
//            String[] mMediaPaths = mMPM.mProject.getMediaAsPathArray();
//
//            //add videos
//            for (String path : mMediaPaths)
//            {
//                fileList.add(new File(path));
//            }
//
//            //add thumbnails
//            fileList.addAll(Arrays.asList(fileProjectSrc.listFiles()));
//
//            //add database file
//            fileList.add(getDatabasePath("sm.db"));
//
//            FileOutputStream fos = new FileOutputStream(mZipFileName);
//            ZipOutputStream zos = new ZipOutputStream(fos);
//
//            exportProjectFiles(zos, fileList.toArray(new File[fileList.size()]));
//
//            zos.close();
//
////            onExportProjectSuccess(mZipFileName); // FIXME TODO
//        }
//        catch (IOException ioe)
//        {
//            Log.e(AppConstants.TAG, "Error creating zip file:", ioe);
//        }
//    }
//
//
//    private void exportProjectFiles(ZipOutputStream zos, File[] fileList)
//    {
//        final int BUFFER = 2048;
//
//        for (int i = 0; i < fileList.length; i++)
//        {
//            try
//            {
//                byte[] data = new byte[BUFFER];
//
//                FileInputStream fis = new FileInputStream(fileList[i]);
//                zos.putNextEntry(new ZipEntry(fileList[i].getName()));
//
//                int count;
//                while ((count = fis.read(data, 0, BUFFER)) != -1)
//                {
//                    zos.write(data, 0, count);
//                }
//
//                //close steams
//                zos.closeEntry();
//                fis.close();
//
//            }
//            catch (IOException ioe)
//            {
//                Log.e(AppConstants.TAG, "Error creating zip file:", ioe);
//            }
//        }
//    }

    private void showSPLSelectorPopup(final String[] names, final String[] paths) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle("Choose Story File(SdCard/Liger/)").setItems(names, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int index) {
                launchLiger(HomeActivity.this, null, null, paths[index]);
            }
        });

        AlertDialog alert = builder.create();
        alert.show();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
    }


	private void checkForTor ()
    {
    	 SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

	     boolean useTor = settings.getBoolean("pusetor", false);
	     
	     if (useTor)
	     {
	    	 OrbotHelper oh = new OrbotHelper(this);
	    	 
	    	 if (!oh.isOrbotInstalled())
	    	 {
	    		 oh.promptToInstall(this);
	    	 }
	    	 else if (!oh.isOrbotRunning())
	    	 {
	    		 oh.requestOrbotStart(this);
	    	 }
	    	 
	     }
    }

    //if the user hasn't registered with the user, show the login screen
    private void checkCreds ()
    {
    	
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
       
        String user = settings.getString("user", null);
        
        if (user == null)
        {
        	Intent intent = new Intent(this,LoginActivity.class);
        	startActivity(intent);
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_home, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (item.getItemId() == android.R.id.home)
        {
            toggleDrawer();
            return true;
        }
        else if (item.getItemId() == R.id.menu_new_project)
        {
            // need to check this to determine whether there is a storage issue that will cause a crash
            File actualStorageDirectory = StorageHelper.getActualStorageDirectory(this);

            if (actualStorageDirectory != null) {
                launchLiger(this, "default_library", null, null);
            } else {
                //show storage error message
                new AlertDialog.Builder(this)
                        .setTitle(Utils.getAppName(this))
                        .setIcon(android.R.drawable.ic_dialog_info)
                        .setMessage(R.string.err_storage_not_available)
                        .show();
            }

            return true;
        }
        else if (item.getItemId() == R.id.menu_about)
        {
            String url = "https://storymaker.org";

            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            startActivity(i);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static void launchLiger(Context context, String splId, String instancePath, String splPath) {

        // TEMP - do we need to check files for anything besides the default library?
        /*
        if (!DownloadHelper.checkAllFiles(context)) { // FIXME the app should define these, not the library
            Toast.makeText(context, "Please wait for the content pack to finish downloading", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
            return;
        }
        */

        if ((splId != null) && (splId.equals("default_library"))) {

            // initiate check/download for main/patch expansion files
            boolean readyToOpen = DownloadHelper.checkAndDownloadNew(context);

            if (!readyToOpen) {
                // if file is being downloaded, don't open
                Log.d("NEW ITEM CLICK", "CURRENTLY DOWNLOADING FILE");

                Toast.makeText(context, "Please wait for this content pack to finish downloading", Toast.LENGTH_LONG).show(); // FIXME move to strings.xml
                return;
            }

        }

        Intent ligerIntent = new Intent(context, MainActivity.class);
        ligerIntent.putExtra(MainActivity.INTENT_KEY_WINDOW_TITLE, Utils.getAppName(context));
        String lang = StoryMakerApp.getCurrentLocale().getLanguage();
        ligerIntent.putExtra("lang", lang);

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        int pslideduration = Integer.parseInt(settings.getString("pslideduration", "5"));
        ligerIntent.putExtra("photo_essay_slide_duration", pslideduration * 1000);
        if (splId != null && !splId.isEmpty()) {
            ligerIntent.putExtra(MainActivity.INTENT_KEY_STORYPATH_LIBRARY_ID, splId);
        } else if (splPath != null && !splPath.isEmpty()) {
            ligerIntent.putExtra(MainActivity.INTENT_KEY_STORYPATH_LIBRARY_PATH, splPath);
        } else if (instancePath != null && !instancePath.isEmpty()) {
            ligerIntent.putExtra(MainActivity.INTENT_KEY_STORYPATH_INSTANCE_PATH, instancePath);
        }
        context.startActivity(ligerIntent);
    }

	private void showPreferences ()
	{
		Intent intent = new Intent(this,SimplePreferences.class);
		this.startActivityForResult(intent, 9999);
	}

	@Override
	protected void onActivityResult(int arg0, int arg1, Intent arg2) {
		
		super.onActivityResult(arg0, arg1, arg2);

		boolean changed = ((StoryMakerApp)getApplication()).checkLocale();
		if (changed)
		{
			finish();
			startActivity(new Intent(this,HomeActivity.class));
			
		}
	}
	
	public class MyAdapter extends FragmentPagerAdapter {
		 
		 int[] mMessages;
		 int[] mTitles;
		 
	        public MyAdapter(FragmentManager fm, int[] titles, int[] messages) {
	            super(fm);
	            mTitles = titles;
	            mMessages = messages;
	        }

	        @Override
	        public int getCount() {
	            return mMessages.length;
	        }

	        @Override
	        public Fragment getItem(int position) {
	        	Bundle bundle = new Bundle();
	        	bundle.putString("title",getString(mTitles[position]));
	        	bundle.putString("msg", getString(mMessages[position]));
	        	
	        	Fragment f = new MyFragment();
	        	f.setArguments(bundle);
	        	
	            return f;
	        }
	    }
	
	public static final class MyFragment extends Fragment {
	
		String mMessage;
		String mTitle;
		
		 /**
       * When creating, retrieve this instance's number from its arguments.
       */
      @Override
      public void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);

          mTitle = getArguments().getString("title");
          mMessage = getArguments().getString("msg");
      }

      /**
       * The Fragment's UI is just a simple text view showing its
       * instance number.
       */
      @Override
      public View onCreateView(LayoutInflater inflater, ViewGroup container,
              Bundle savedInstanceState) {
          
          ViewGroup root = (ViewGroup) inflater.inflate(R.layout.card_pager_textview, null);
          
          ((TextView)root.findViewById(R.id.title)).setText(mTitle);
          
          ((TextView)root.findViewById(R.id.description)).setText(mMessage);
          
          return root;
      }
	
	}
	
	private void checkForCrashes() {
	    //CrashManager.register(this, AppConstants.HOCKEY_APP_ID);
        CrashManager.register(this, AppConstants.HOCKEY_APP_ID, new CrashManagerListener() {
            public String getDescription() {
                String description = "";

                try {
                    //Process process = Runtime.getRuntime().exec("logcat -d HockeyApp:D *:S");
                    Process process = Runtime.getRuntime().exec("logcat -d");
                    BufferedReader bufferedReader =
                            new BufferedReader(new InputStreamReader(process.getInputStream()));

                    StringBuilder log = new StringBuilder();
                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        log.append(line);
                        log.append(System.getProperty("line.separator"));
                    }
                    bufferedReader.close();

                    description = log.toString();
                }
                catch (IOException e) {
                }

                return description;
            }
        });
	 }

    private void checkForUpdates() {
        if (BuildConfig.DEBUG) {
            UpdateManager.register(this, AppConstants.HOCKEY_APP_ID);
        }
    }

    public void downloadComplete() {
        //this.downloadPoller = null;
        initActivityList();
        // http://stackoverflow.com/questions/2745061/java-lang-illegalargumentexception-view-not-attached-to-window-manager
        try {
            if ((this.mLoading != null) && this.mLoading.isShowing()) {
                this.mLoading.dismiss();
            }
        } catch (final IllegalArgumentException e) {
            // Handle or log or ignore
        } catch (final Exception e) {
            // Handle or log or ignore
        } finally {
            this.mLoading = null;
        }
    }

    // FIXME once we have a patch as well as a main file this gets a little more complex
    // i think this can be removed, individual menu items are now locked during downloads
    /*
    class DownloadPoller extends AsyncTask<String, Long, Integer> {

        protected void onPreExecute() {
            super.onPreExecute();

            if (mLoading == null || (!mLoading.isShowing())) {
                boolean indeterminate= true;
                float prog = DownloadHelper.getDownloadProgress(HomeActivity.this);
                if (prog != -1.0) {
                    indeterminate = false;
                }
                mLoading = ProgressDialog.show(HomeActivity.this, null, "Downloading content...", indeterminate, true);
                mLoading.setIndeterminate(indeterminate);
                mLoading.setCancelable(false);
                mLoading.setCanceledOnTouchOutside(false);
            }
        }

        protected Integer doInBackground(String... params) {
            while (!DownloadHelper.checkAllFiles(HomeActivity.this)) {
                // TODO add progress
                float prog = DownloadHelper.getDownloadProgress(HomeActivity.this);
                boolean indeterminate = mLoading.isIndeterminate();
                if (!indeterminate && prog >= 0f) {
                    int dialogProg = Math.round(10000 * prog);
                    mLoading.setProgress(dialogProg);
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        protected void onPostExecute(Integer result) {
            super.onPostExecute(result);

            HomeActivity.this.downloadComplete();
        }
    }
    */
}
