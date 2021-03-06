/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package ca.psiphon.ploggy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import android.content.Context;

/**
 * Data persistence for self, friends, and status.
 *
 * On disk, data is represented as JSON stored in individual files. In memory, data is represented
 * as immutable POJOs which are thread-safe and easily serializable. Self and friend metadata, including
 * identity, and recent status data are kept in-memory. Large data such as map tiles will be left on
 * disk with perhaps an in-memory cache.
 * 
 * Simple consistency is provided: data changes are first written to a commit file, then the commit
 * file replaces the data file. In memory structures are replaced only after the file write succeeds.
 * 
 * If local security is added to the scope of Ploggy, here's where we'd interface with SQLCipher and/or
 * KeyChain, etc.
 */
public class Data {
    
    private static final String LOG_TAG = "Data";
    
    public static class Self {
        public final Identity.PublicIdentity mPublicIdentity;
        public final Identity.PrivateIdentity mPrivateIdentity;
        public final Date mCreatedTimestamp;

        public Self(
                Identity.PublicIdentity publicIdentity,
                Identity.PrivateIdentity privateIdentity,
                Date createdTimestamp) {
            mPublicIdentity = publicIdentity;
            mPrivateIdentity = privateIdentity;
            mCreatedTimestamp = createdTimestamp;
        }
    }
    
    public static class Friend {
        public final String mId;
        public final Identity.PublicIdentity mPublicIdentity;
        public final Date mAddedTimestamp;
        public final Date mLastSentStatusTimestamp;
        public final Date mLastReceivedStatusTimestamp;

        public Friend(
                Identity.PublicIdentity publicIdentity,
                Date addedTimestamp) throws Utils.ApplicationError {
            this(publicIdentity, addedTimestamp, null, null);
        }
        public Friend(
                Identity.PublicIdentity publicIdentity,
                Date addedTimestamp,
                Date lastSentStatusTimestamp,
                Date lastReceivedStatusTimestamp) throws Utils.ApplicationError {
            mId = Utils.formatFingerprint(publicIdentity.getFingerprint());
            mPublicIdentity = publicIdentity;
            mAddedTimestamp = addedTimestamp;
            mLastSentStatusTimestamp = lastSentStatusTimestamp;
            mLastReceivedStatusTimestamp = lastReceivedStatusTimestamp;
        }
    }
    
    public static class Message {
        public final Date mTimestamp;
        public final String mContent;

        public Message(
                Date timestamp,
                String content) {
            mTimestamp = timestamp;
            mContent = content;
        }
    }
    
    public static class Location {
        public final Date mTimestamp;
        public final double mLatitude;
        public final double mLongitude;
        public final int mPrecision;
        public final String mStreetAddress;

        public Location(
                Date timestamp,
                double latitude,
                double longitude,
                int precision,
                String streetAddress) {
            mTimestamp = timestamp;
            mLatitude = latitude;
            mLongitude = longitude;
            mPrecision = precision;
            mStreetAddress = streetAddress;            
        }
    }
    
    public static class Status {
        final List<Message> mMessages;
        public final Location mLocation;

        public Status(
                List<Message> messages,
                Location location) {
            mMessages = messages;
            mLocation = location;
        }
    }
    
    public static class DataNotFoundError extends Utils.ApplicationError {
        private static final long serialVersionUID = -8736069103392081076L;
        
        public DataNotFoundError() {
            // No log for this expected condition
            super(null, "");
        }
    }

    public static class DataAlreadyExistsError extends Utils.ApplicationError {
        private static final long serialVersionUID = 6287628326991088141L;

        public DataAlreadyExistsError() {
            // No log for this expected condition
            super(null, "");
        }
    }

    // ---- Singleton ----
    private static Data instance = null;
    public static synchronized Data getInstance() {
       if(instance == null) {
          instance = new Data();
       }
       return instance;
    }
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }
    // -------------------

    // TODO: SQLCipher/IOCipher storage? key/value store?
    // TODO: use http://nelenkov.blogspot.ca/2011/11/using-ics-keychain-api.html?
    // ...consistency: write file, then update in-memory; 2pc; only for short lists of friends
    // ...eventually use file system for map tiles etc.
       
    private static final String DATA_DIRECTORY = "ploggyData"; 
    private static final String SELF_FILENAME = "self.json"; 
    private static final String SELF_STATUS_FILENAME = "selfStatus.json"; 
    private static final String FRIENDS_FILENAME = "friends.json"; 
    private static final String FRIEND_STATUS_FILENAME_FORMAT_STRING = "%s-friendStatus.json"; 
    private static final String COMMIT_FILENAME_SUFFIX = ".commit"; 
    
    Self mSelf;
    Status mSelfStatus;
    ArrayList<Friend> mFriends;
    HashMap<String, Status> mFriendStatuses;

    public synchronized void reset() throws Utils.ApplicationError {
        // Warning: deletes all files in DATA_DIRECTORY (not recursively)
        File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
        directory.mkdirs();
        boolean deleteFailed = false;
        for (String child : directory.list()) {
            File file = new File(directory, child);
            if (file.isFile()) {
                if (!file.delete()) {
                    deleteFailed = true;
                    // Keep attempting to delete remaining files...
                }
            }
        }
        if (deleteFailed) {
            throw new Utils.ApplicationError(LOG_TAG, "delete data file failed");
        }
    }
    
    public synchronized Self getSelf() throws Utils.ApplicationError, DataNotFoundError {
        if (mSelf == null) {
            mSelf = Json.fromJson(readFile(SELF_FILENAME), Self.class);
        }
        return mSelf;
    }

    public synchronized void updateSelf(Self self) throws Utils.ApplicationError {
        // When creating a new identity, remove status from previous identity
        deleteFile(String.format(SELF_STATUS_FILENAME));
        writeFile(SELF_FILENAME, Json.toJson(self));
        mSelf = self;
        Log.addEntry(LOG_TAG, "updated your identity");
        Events.post(new Events.UpdatedSelf());
    }

    public synchronized Status getSelfStatus() throws Utils.ApplicationError {
        if (mSelfStatus == null) {
            try {
                mSelfStatus = Json.fromJson(readFile(SELF_STATUS_FILENAME), Status.class);
            } catch (DataNotFoundError e) {
                // If there's no previous status, return a blank one
                return new Status(new ArrayList<Message>(), new Location(null, 0, 0, 0, null));
            }
        }
        return mSelfStatus;
    }

    public synchronized void addSelfStatusMessage(Data.Message message) throws Utils.ApplicationError, DataNotFoundError {
        Status currentStatus = getSelfStatus();
        ArrayList<Message> messages = new ArrayList<Message>(currentStatus.mMessages);
        messages.add(0, message);
        while (messages.size() > Protocol.MAX_MESSAGE_COUNT) {
            messages.remove(messages.size() - 1);
        }
        Status newStatus = new Status(messages, currentStatus.mLocation);
        writeFile(SELF_STATUS_FILENAME, Json.toJson(newStatus));
        mSelfStatus = newStatus;
        Log.addEntry(LOG_TAG, "added your message");
        Events.post(new Events.UpdatedSelfStatus());
    }

    public synchronized void updateSelfStatusLocation(Data.Location location) throws Utils.ApplicationError {
        Status currentStatus = getSelfStatus();
        Status newStatus = new Status(currentStatus.mMessages, location);
        writeFile(SELF_STATUS_FILENAME, Json.toJson(newStatus));
        mSelfStatus = newStatus;
        Log.addEntry(LOG_TAG, "updated your location");
        Events.post(new Events.UpdatedSelfStatus());
    }

    private void loadFriends() throws Utils.ApplicationError {
        if (mFriends == null) {
            try {
                mFriends = new ArrayList<Friend>(Arrays.asList(Json.fromJson(readFile(FRIENDS_FILENAME), Friend[].class)));
            } catch (DataNotFoundError e) {
                mFriends = new ArrayList<Friend>();
            }
        }
    }
    
    public synchronized final ArrayList<Friend> getFriends() throws Utils.ApplicationError {
        loadFriends();
        return new ArrayList<Friend>(mFriends);
    }

    public synchronized Friend getFriendById(String id) throws Utils.ApplicationError, DataNotFoundError {
        loadFriends();
        synchronized(mFriends) {
            for (Friend friend : mFriends) {
                if (friend.mId.equals(id)) {
                    return friend;
                }
            }
        }
        throw new DataNotFoundError();
    }

    public synchronized Friend getFriendByNickname(String nickname) throws Utils.ApplicationError, DataNotFoundError {
        loadFriends();
        synchronized(mFriends) {
            for (Friend friend : mFriends) {
                if (friend.mPublicIdentity.mNickname.equals(nickname)) {
                    return friend;
                }
            }
        }
        throw new DataNotFoundError();
    }

    public synchronized Friend getFriendByCertificate(String certificate) throws Utils.ApplicationError, DataNotFoundError {
        loadFriends();
        synchronized(mFriends) {
            for (Friend friend : mFriends) {
                if (friend.mPublicIdentity.mX509Certificate.equals(certificate)) {
                    return friend;
                }
            }
        }
        throw new DataNotFoundError();
    }

    public synchronized void addFriend(Friend friend) throws Utils.ApplicationError {
        loadFriends();
        synchronized(mFriends) {
            boolean friendWithIdExists = true;
            boolean friendWithNicknameExists = true;
            try {
                getFriendById(friend.mId);
            } catch (DataNotFoundError e) {
                friendWithIdExists = false;
            }
            try {
                getFriendByNickname(friend.mPublicIdentity.mNickname);
            } catch (DataNotFoundError e) {
                friendWithNicknameExists = false;
            }
            // TODO: report which conflict occurred
            if (friendWithIdExists || friendWithNicknameExists) {
                throw new DataAlreadyExistsError();
            }
            ArrayList<Friend> newFriends = new ArrayList<Friend>(mFriends);
            newFriends.add(friend);
            writeFile(FRIENDS_FILENAME, Json.toJson(newFriends));
            mFriends.add(friend);
            Log.addEntry(LOG_TAG, "added friend: " + friend.mPublicIdentity.mNickname);
            Events.post(new Events.AddedFriend(friend.mId));
        }
    }

    private void updateFriendHelper(List<Friend> list, Friend friend) throws DataNotFoundError {
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).mId.equals(friend.mId)) {
                list.set(i, friend);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new DataNotFoundError();
        }
    }

    public synchronized void updateFriend(Friend friend) throws Utils.ApplicationError {
        loadFriends();
        synchronized(mFriends) {
            ArrayList<Friend> newFriends = new ArrayList<Friend>(mFriends);
            updateFriendHelper(newFriends, friend);
            writeFile(FRIENDS_FILENAME, Json.toJson(newFriends));
            updateFriendHelper(mFriends, friend);
            Log.addEntry(LOG_TAG, "updated friend: " + friend.mPublicIdentity.mNickname);
            Events.post(new Events.UpdatedFriend(friend.mId));
        }
    }

    public synchronized Date getFriendLastSentStatusTimestamp(String friendId) throws Utils.ApplicationError {
        Friend friend = getFriendById(friendId);
        return friend.mLastSentStatusTimestamp;
    }
    
    public synchronized void updateFriendLastSentStatusTimestamp(String friendId) throws Utils.ApplicationError {
        // TODO: don't write an entire file for each timestamp update!
        Friend friend = getFriendById(friendId);
        updateFriend(
            new Friend(
                friend.mPublicIdentity,
                friend.mAddedTimestamp,
                new Date(),
                friend.mLastReceivedStatusTimestamp));
    }
    
    public synchronized Date getFriendLastReceivedStatusTimestamp(String friendId) throws Utils.ApplicationError {
        Friend friend = getFriendById(friendId);
        return friend.mLastReceivedStatusTimestamp;
    }
    
    public synchronized void updateFriendLastReceivedStatusTimestamp(String friendId) throws Utils.ApplicationError {
        // TODO: don't write an entire file for each timestamp update!
        Friend friend = getFriendById(friendId);
        updateFriend(
            new Friend(
                friend.mPublicIdentity,
                friend.mAddedTimestamp,
                friend.mLastSentStatusTimestamp,
                new Date()));
    }
    
    private void removeFriendHelper(String id, List<Friend> list) throws DataNotFoundError {
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).mId.equals(id)) {
                list.remove(i);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new DataNotFoundError();
        }
    }

    public synchronized void removeFriend(String id) throws Utils.ApplicationError, DataNotFoundError {
        loadFriends();
        synchronized(mFriends) {
            Friend friend = getFriendById(id);
            deleteFile(String.format(FRIEND_STATUS_FILENAME_FORMAT_STRING, id));
            ArrayList<Friend> newFriends = new ArrayList<Friend>(mFriends);
            removeFriendHelper(id, newFriends);
            writeFile(FRIENDS_FILENAME, Json.toJson(newFriends));
            removeFriendHelper(id, mFriends);
            Log.addEntry(LOG_TAG, "removed friend: " + friend.mPublicIdentity.mNickname);
            Events.post(new Events.RemovedFriend(id));
        }
    }

    public synchronized Status getFriendStatus(String id) throws Utils.ApplicationError, DataNotFoundError {
        String filename = String.format(FRIEND_STATUS_FILENAME_FORMAT_STRING, id);
        return Json.fromJson(readFile(filename), Status.class);
    }

    public synchronized void updateFriendStatus(String id, Status status) throws Utils.ApplicationError {
        Friend friend = getFriendById(id);
        Status previousStatus = null;
        try {
            previousStatus = getFriendStatus(id);
            
            // Mitigate push/pull race condition where older status overwrites newer status
            // TODO: more robust protocol... don't rely on clocks
            if ((previousStatus.mMessages.size() > 0 &&
                    (status.mMessages.size() < previousStatus.mMessages.size()
                     || status.mMessages.get(0).mTimestamp.before(previousStatus.mMessages.get(0).mTimestamp))) ||
               ((previousStatus.mLocation != null &&
                    (status.mLocation == null
                     || status.mLocation.mTimestamp.before(previousStatus.mLocation.mTimestamp))))) {
                Log.addEntry(LOG_TAG, "discarded stale friend status: " + friend.mPublicIdentity.mNickname);
                return;
            }
        } catch (DataNotFoundError e) {
        }
        String filename = String.format(FRIEND_STATUS_FILENAME_FORMAT_STRING, id);
        writeFile(filename, Json.toJson(status));
        Log.addEntry(LOG_TAG, "updated friend status: " + friend.mPublicIdentity.mNickname);
        Events.post(new Events.UpdatedFriendStatus(friend, status, previousStatus));
    }

    private static String readFile(String filename) throws Utils.ApplicationError, DataNotFoundError {
        FileInputStream inputStream = null;
        try {
            File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
            String commitFilename = filename + COMMIT_FILENAME_SUFFIX;
            File commitFile = new File(directory, commitFilename);
            File file = new File(directory, filename);
            replaceFileIfExists(commitFile, file);
            inputStream = new FileInputStream(file);
            return Utils.readInputStreamToString(inputStream);
        } catch (FileNotFoundException e) {
            throw new DataNotFoundError();
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
        }        
    }

    private static void writeFile(String filename, String value) throws Utils.ApplicationError {
        FileOutputStream outputStream = null;
        try {
            File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
            String commitFilename = filename + COMMIT_FILENAME_SUFFIX;
            File commitFile = new File(directory, commitFilename);
            File file = new File(directory, filename);
            outputStream = new FileOutputStream(commitFile);
            outputStream.write(value.getBytes());
            outputStream.close();
            replaceFileIfExists(commitFile, file);
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private static void replaceFileIfExists(File commitFile, File file) throws IOException {
        if (commitFile.exists()) {
            file.delete();
            commitFile.renameTo(file);
        }
    }
    
    private static void deleteFile(String filename) throws Utils.ApplicationError {
        File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
        File file = new File(directory, filename);
        if (!file.delete()) {
            if (file.exists()) {
                throw new Utils.ApplicationError(LOG_TAG, "failed to delete file");
            }
        }
    }
}
