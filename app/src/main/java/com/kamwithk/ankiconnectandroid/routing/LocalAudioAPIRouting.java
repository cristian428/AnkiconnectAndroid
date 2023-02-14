package com.kamwithk.ankiconnectandroid.routing;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.kamwithk.ankiconnectandroid.request_parsers.Parser;
import com.kamwithk.ankiconnectandroid.routing.localaudiosource.ForvoAudioSource;
import com.kamwithk.ankiconnectandroid.routing.localaudiosource.JPodAltAudioSource;
import com.kamwithk.ankiconnectandroid.routing.localaudiosource.JPodAudioSource;
import com.kamwithk.ankiconnectandroid.routing.localaudiosource.LocalAudioSource;
import com.kamwithk.ankiconnectandroid.routing.localaudiosource.NHK16AudioSource;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local audio in AnkidroidAndroid works similarly to the original python script found at
 * https://github.com/Aquafina-water-bottle/jmdict-english-yomichan/tree/master/local_audio
 *
 * Here are the main differences:
 * - The memory-based version is not supported. Only the SQL version is supported.
 * - The SQLite3 database is *NOT* dynamically created. This means that the user must copy/paste
 *   the generated entries.db into the correct place within their Android device. The database
 *   is not created dynamically in order to make the process of implementing this feature
 *   as simple as possible.
 * - The URIs are different:
 *   - initial get:
 *     python:  http://localhost:5050/?sources=jpod,jpod_alternate,nhk16,forvo&term={term}&reading={reading}
 *     android: http://localhost:8765/localaudio/get/&sources=jpod,jpod_alternate,nhk16,forvo&term={term}&reading={reading}
 *   - audio file get:
 *     python:  http://localhost:5050/SOURCE/FILE_PATH_TO_AUDIO_FILE
 *     android: http://localhost:8765/localaudio/SOURCE/FILE_PATH_TO_AUDIO_FILE
 *  - NHK98 is not supported (because the audio files aren't available for the original anyways)
 */
public class LocalAudioAPIRouting {
    //private final File externalFilesDir;
    private final EntriesDbOpenHelper entriesDbHelper;
    private final AndroidDbOpenHelper androidDbHelper;
    private final Map<String, LocalAudioSource> sourceIdToSource;
    public LocalAudioAPIRouting(Context context) {
        //this.externalFilesDir = externalFilesDir;
        this.entriesDbHelper = new EntriesDbOpenHelper(context);
        this.androidDbHelper = new AndroidDbOpenHelper(context);

        this.sourceIdToSource = new HashMap<>();

        this.sourceIdToSource.put("jpod", new JPodAudioSource());
        this.sourceIdToSource.put("jpod_alternate", new JPodAltAudioSource());
        this.sourceIdToSource.put("nhk16", new NHK16AudioSource());
        this.sourceIdToSource.put("forvo", new ForvoAudioSource());
    }

    public NanoHTTPD.Response getAudioSourcesHandleError(Map<String, List<String>> parameters) {
        List<Map<String, String>> audioSourcesResult = new ArrayList<>();

        SQLiteDatabase db = entriesDbHelper.getReadableDatabase();

        // Filter results WHERE "title" = 'My Title'
        String selection = "expression = ?\n" +
                "AND (reading IS NULL OR reading = ?)\n";

        // TODO filters by sources if necessary

        // TODO filters by speakers if necessary

        String[] selectionArgs = { getTerm(parameters), getReading(parameters) };

        // How you want the results sorted in the resulting Cursor
        // TODO order by source
        // TODO order by speakers if necessary
        String sortOrder = null;

        Cursor cursor = db.query(
                "entries",        // The table to query
                null,                   // The array of columns to return (pass null to get all)
                selection,              // The columns for the WHERE clause
                selectionArgs,          // The values for the WHERE clause
                null,                   // don't group the rows
                null,                   // don't filter by row groups
                sortOrder               // The sort order
        );

        while (cursor.moveToNext()) {
            String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
            String file = cursor.getString(cursor.getColumnIndexOrThrow("file"));

            LocalAudioSource audioSource = sourceIdToSource.get(source);
            if (audioSource == null) {
                Log.w("AnkiConnectAndroid", "Unknown audio source: " + source);
                continue;
            }

            String name = audioSource.getSourceName(cursor);
            String url = audioSource.constructFileURL(file);

            Map<String, String> audioSourceEntry = new HashMap<>();
            audioSourceEntry.put("name", name);
            audioSourceEntry.put("url", url);

            audioSourcesResult.add(audioSourceEntry);
        }

        cursor.close();

        // opens database
//        try {



            //String connectionURI = "jdbc:sqlite:" + externalFilesDir + "/entries.db";
            // for this to not error on android, must add .so files manually
            // https://github.com/xerial/sqlite-jdbc/blob/master/USAGE.md#how-to-use-with-android

            //Connection connection = DriverManager.getConnection(connectionURI);

//            String[] sources = Objects.requireNonNull(parameters.get("sources")).get(0).split(",");
//            for (String source : sources) {
//                //Log.d("AnkiConnectAndroid", source + " | " + sourceIdToSource.containsKey(source));
//                if (sourceIdToSource.containsKey(source)) {
//                    List<Map<String, String>> audioSources = Objects.requireNonNull(sourceIdToSource.get(source)).getSources(connection, parameters);
//                    audioSourcesResult.addAll(audioSources);
//                }
//            }
//        } catch (SQLException e) {
//            // if the error message is "out of memory",
//            // it probably means no database file is found
//            Log.e("AnkiConnectAndroid", e.getMessage());
//        }

        Type typeToken = new TypeToken<ArrayList<HashMap<String, String>>>() {}.getType();

        JsonObject response = new JsonObject();
        response.addProperty("type", "audioSourceList");
        response.add("audioSources", Parser.gson.toJsonTree(audioSourcesResult, typeToken));
        Log.d("AnkiConnectAndroid", "audio sources json: " + Parser.gson.toJson(response));

        return newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/json",
                Parser.gson.toJson(response)
        );
    }

    private NanoHTTPD.Response audioError(String msg) {
        Log.w("AnkiConnectAndroid", msg);
        return newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST, // 400, mimics python script
                NanoHTTPD.MIME_PLAINTEXT, msg
        );
    }

    private String getTerm(Map<String, List<String>> parameters) {
        try {
            return parameters.get("term").get(0);
        } catch (NullPointerException e) {
            return parameters.get("expression").get(0);
        }
    }

    private String getReading(Map<String, List<String>> parameters) {
        return Objects.requireNonNull(parameters.get("reading")).get(0);
    }

    public NanoHTTPD.Response getAudioHandleError(String source, String path) {
        if (!sourceIdToSource.containsKey(source)) {
            return audioError("Unknown source: " + source);
        }

        SQLiteDatabase db = androidDbHelper.getReadableDatabase();

        String selection = "file = ? AND source = ?";
        String[] selectionArgs = { path, source };

        Cursor cursor = db.query(
                "android",        // The table to query
                null,                   // The array of columns to return (pass null to get all)
                selection,              // The columns for the WHERE clause
                selectionArgs,          // The values for the WHERE clause
                null,                   // don't group the rows
                null,                   // don't filter by row groups
                null                    // The sort order
        );

        if (!cursor.moveToNext()) {
            return audioError("File not found in query: " + path);
        }
        byte[] data = cursor.getBlob(cursor.getColumnIndexOrThrow("data"));
        cursor.close();

        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types
        if (path.endsWith(".mp3")) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "audio/mpeg", new ByteArrayInputStream(data), data.length);
        } else if (path.endsWith(".aac")) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "audio/aac", new ByteArrayInputStream(data), data.length);
        } else {
            return audioError("File is neither a .mp3 or .acc file: " + path);
        }



//        String mediaDir = Objects.requireNonNull(sourceIdToSource.get(source)).getMediaDir();
//        String fullPath = externalFilesDir + "/" + mediaDir + "/" + path;

//        File f = new File(fullPath);
//        if (!f.exists()) {
//            return audioError("File does not exist: " + fullPath);
//        }


//        String connectionURI = "jdbc:sqlite:" + externalFilesDir + "/android.db";
//
//        try {
//            Connection connection = DriverManager.getConnection(connectionURI);
//            String query = "SELECT data FROM android WHERE file = ? AND source = ?";
//            PreparedStatement pstmt = connection.prepareStatement(query);
//            // indices start at 1
//            pstmt.setString(1, path);
//            pstmt.setString(2, source);
//
//            pstmt.setQueryTimeout(3);  // set timeout to 3 sec.
//            ResultSet rs = pstmt.executeQuery();
//            if (!rs.next()) {
//                return audioError("File not found in query: " + path);
//            }
//            byte[] data = rs.getBytes("data");
//
//            // https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types/Common_types
//            if (path.endsWith(".mp3")) {
//                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "audio/mpeg", new ByteArrayInputStream(data), data.length);
//            } else if (path.endsWith(".aac")) {
//                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "audio/aac", new ByteArrayInputStream(data), data.length);
//            } else {
//                return audioError("File is neither a .mp3 or .acc file: " + path);
//            }
//
//        } catch (SQLException e) {
//            e.printStackTrace();
//            return audioError("File could not be read: " + path);
//        }
    }
}
