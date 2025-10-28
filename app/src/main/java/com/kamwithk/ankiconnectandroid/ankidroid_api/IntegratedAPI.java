package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION;

import com.kamwithk.ankiconnectandroid.request_parsers.MediaRequest;
import com.ichi2.anki.FlashCardsContract;
import com.ichi2.anki.api.AddContentApi;
import com.kamwithk.ankiconnectandroid.request_parsers.NoteRequest;

public class IntegratedAPI {
    private Context context;
    public final DeckAPI deckAPI;
    public final ModelAPI modelAPI;
    public final NoteAPI noteAPI;
    public final MediaAPI mediaAPI;
    private final AddContentApi api; // TODO: Combine all API classes???

    //From anki-connect repo
    private static final String CAN_ADD_ERROR_DUPLICATE = "cannot create note because it is a duplicate";
    private static final String CAN_ADD_ERROR_EMPTY_MODEL = "model was not found: ";
    private static final String CAN_ADD_ERROR_EMPTY_DECK_NAME = "deck was not found: ";
    private static final String CAN_ADD_ERROR_EMPTY = "cannot create note because it is empty";
    private static final String CAN_ADD_ERROR_UNKNOWN = "cannot create note for unknown reason";
    public IntegratedAPI(Context context) {
        this.context = context;

        deckAPI = new DeckAPI(context);
        modelAPI = new ModelAPI(context);
        noteAPI = new NoteAPI(context);
        mediaAPI = new MediaAPI(context);

        api = new AddContentApi(context);
    }

    public static void authenticate(Context context) {
        int permission = ContextCompat.checkSelfPermission(context, READ_WRITE_PERMISSION);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity)context, new String[]{READ_WRITE_PERMISSION}, 0);
        }
    }

    //public File getExternalFilesDir() {
    //    return context.getExternalFilesDir(null);
    //}

    public void addSampleCard() {
        Map<String, String> data = new HashMap<>();
        data.put("Back", "sunrise");
        data.put("Front", "日の出");

        try {
            addNote(data, "Temporary", "Basic", null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static class CanAddWithError {
        private final boolean canAdd;
        private final String error;

        public CanAddWithError(boolean canAdd, String error) {
            this.canAdd = canAdd;
            this.error = error;
        }

        public boolean isCanAdd() {
            return canAdd;
        }

        public String getError() {
            return error;
        }
    }

    private static class NoteInfo {
        final NoteRequest note;
        Long checksum = null;
        Long modelId = null;
        boolean duplicateCheck = false;
        CanAddWithError canAddWithError;
        Set<Long> deckIds = new HashSet<>();
        private NoteInfo(NoteRequest note) {
            this.note = note;
        }
    }

    private static class DuplicateNote {
        long id;
        long mid;

        private DuplicateNote(long id, long mid) {
            this.id = id;
            this.mid = mid;
        }
    }

    public List<Boolean> canAddNotes(List<NoteRequest> notes) {
        return canAddNotesWithErrorDetail(notes).stream()
                .map(CanAddWithError::isCanAdd)
                .collect(Collectors.toList());
    }

    public List<CanAddWithError> canAddNotesWithErrorDetail(List<NoteRequest> notes) {
        if (notes == null || notes.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Long> deckNamesToIds;
        Map<String, Long> modelNameToId;
        try {
            deckNamesToIds = deckAPI.deckNamesAndIds();
            modelNameToId = modelAPI.modelNamesAndIds(0);
        } catch (Exception e) {
            return notes.stream()
                    .map(n -> new CanAddWithError(false, CAN_ADD_ERROR_UNKNOWN))
                    .collect(Collectors.toList());
        }

        List<NoteInfo> noteInfos = new ArrayList<>(notes.size());
        Set<Long> checksums = new HashSet<>();
        for (NoteRequest note : notes) {
            NoteInfo noteInfo = validateNoteRequest(note, modelNameToId, deckNamesToIds);
            noteInfos.add(noteInfo);
            if (noteInfo.duplicateCheck) {
                checksums.add(noteInfo.checksum);
            }
        }

        if (checksums.isEmpty()) {
            return noteInfos.stream()
                    .map(n -> n.canAddWithError)
                    .collect(Collectors.toList());
        }

        Map<Long, Set<DuplicateNote>> duplicateNotes = getDuplicateNotes(checksums);

        for (NoteInfo noteInfo : noteInfos) {
            if (!noteInfo.duplicateCheck) {
                continue;
            }

            duplicateCheck(noteInfo, duplicateNotes);
        }

        return noteInfos.stream()
                .map(ninfo -> ninfo.canAddWithError)
                .collect(Collectors.toList());
    }

    private void duplicateCheck(NoteInfo noteInfo, Map<Long, Set<DuplicateNote>> duplicateNotes) {
        Set<DuplicateNote> duplicates = duplicateNotes.get(noteInfo.checksum);
        if (duplicates == null) {
            noteInfo.canAddWithError = new CanAddWithError(true, null);
            return;
        }

        boolean isDeckScope = noteInfo.note.getOptions().getDuplicateScope().equals("deck");
        boolean checkAllModels = noteInfo.note.getOptions().isCheckAllModels();
        boolean hasDuplicate = false;
        for (DuplicateNote duplicate : duplicates) {
            // filter by model here instead of in query
            if (!checkAllModels && noteInfo.modelId != duplicate.mid) {
                continue;
            }

            if (isDeckScope) {
                if (isNoteInDeck(duplicate.id, noteInfo.deckIds)) {
                    hasDuplicate = true;
                    break;
                }
            }
            else {
                hasDuplicate = true;
                break;
            }
        }

        noteInfo.canAddWithError = hasDuplicate ? new CanAddWithError(false, CAN_ADD_ERROR_DUPLICATE) : new CanAddWithError(true, null);
    }

    private Map<Long, Set<DuplicateNote>> getDuplicateNotes(Set<Long> unprocessedChecksums) {
        final String[] NOTE_PROJECTION = {
                FlashCardsContract.Note._ID,
                FlashCardsContract.Note.CSUM,
                FlashCardsContract.Note.MID
        };

        String checksums = TextUtils.join(",", unprocessedChecksums);
        String selection = String.format(
                Locale.US,
                "%s in (%s)",
                FlashCardsContract.Note.CSUM,
                checksums
        );

        // as a checksum can have multiple notes this has to be a map
        Map<Long, Set<DuplicateNote>> duplicateNotes = new HashMap<>();
        // this is basically findChecksumsInQuery, we collect all duplicate notes for each checksum
        try (Cursor cursor = context.getContentResolver().query(
                FlashCardsContract.Note.CONTENT_URI_V2,
                NOTE_PROJECTION,
                selection,
                null,
                null
        )) {
            if (cursor != null) {
                int idIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note._ID);
                int midIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.MID);
                int csumIdx = cursor.getColumnIndexOrThrow(FlashCardsContract.Note.CSUM);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idIdx);
                    long mid = cursor.getLong(midIdx);
                    long csum = cursor.getLong(csumIdx);

                    Set<DuplicateNote> set = duplicateNotes.computeIfAbsent(csum, k -> new HashSet<>());
                    set.add(new DuplicateNote(id, mid));
                }
            }
        } catch (Exception e) {
            // assume no duplicates if query fails
            return Collections.emptyMap();
        }
        return duplicateNotes;
    }

    private NoteInfo validateNoteRequest(NoteRequest note, Map<String, Long> modelNameToId, Map<String, Long> deckNamesToIds) {
        NoteInfo result = new NoteInfo(note);
        NoteRequest.NoteOptions noteOptions = note.getOptions();

        String modelName = note.getModelName();
        if (modelName == null || modelName.isEmpty()) {
            result.canAddWithError = new CanAddWithError(false, CAN_ADD_ERROR_EMPTY_MODEL);
            return result;
        }

        result.modelId = modelNameToId.get(modelName);
        if (result.modelId == null) {
            result.canAddWithError = new CanAddWithError(false, CAN_ADD_ERROR_EMPTY_MODEL + modelName);
            return result;
        }

        String deckName = noteOptions.getDeckName();
        if (deckName == null) {
            // Deck, not root
            deckName = note.getDeckName();
            if (deckName == null || deckName.isEmpty()) {
                result.canAddWithError = new CanAddWithError(false, CAN_ADD_ERROR_EMPTY_DECK_NAME);
                return result;
            }
            result.deckIds.add(deckNamesToIds.get(deckName));
        }
        else {
            for (String name : deckNamesToIds.keySet()) {
                if (name.contains(deckName)) {
                    result.deckIds.add(deckNamesToIds.get(name));
                }
            }
        }

        if (note.getFieldName() == null && note.getFieldValue() == null) {
            result.canAddWithError = new CanAddWithError(false, CAN_ADD_ERROR_EMPTY);
            return result;
        }

        result.checksum = Utility.getFieldChecksum(note.getFieldValue());

        // If duplicates are allowed, just need to see if they are valid notes (checksum != 0)
        if (noteOptions.isAllowDuplicate()) {
            result.canAddWithError = (result.checksum == 0) ?
                    new CanAddWithError(false, CAN_ADD_ERROR_UNKNOWN) : new CanAddWithError(true, null);
            return result;
        }

        // Note needs duplicate checking
        result.duplicateCheck = true;
        return result;
    }

    private boolean isNoteInDeck(long noteId, Set<Long> deckIds) {
        // Need to search for all cards with the same note ID, and see if they exist in one of the decks.
        final String[] CARD_PROJECTION = {FlashCardsContract.Card.DECK_ID};

        Uri noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, Long.toString(noteId));
        Uri cardUri = Uri.withAppendedPath(noteUri, "cards");
        Cursor cardCursor = context.getContentResolver().query(
                cardUri,
                CARD_PROJECTION,
                null,
                null,
                null
        );

        if(cardCursor != null) {
            try (cardCursor) {
                while(cardCursor.moveToNext()) {
                    int didIdx = cardCursor.getColumnIndexOrThrow(FlashCardsContract.Card.DECK_ID);
                    long did = cardCursor.getLong(didIdx);

                    if (deckIds.contains(did)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
    /**
     * Add flashcards to AnkiDroid through instant add API
     * @param data Map of (field name, field value) pairs
     * @return The id of the note added
     */
    public Long addNote(final Map<String, String> data, String deck_name, String model_name, Set<String> tags) throws Exception {
        Long deck_id = deckAPI.getDeckID(deck_name);
        Long model_id = modelAPI.getModelID(model_name, data.size());
        Long note_id = noteAPI.addNote(data, deck_id, model_id, tags);

        if (note_id != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Card added", Toast.LENGTH_SHORT).show());
            return note_id;
        } else {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Failed to add card", Toast.LENGTH_SHORT).show());
            throw new Exception("Couldn't add note");
        }
    }

    /**
     * Adds the media to the collection, and updates noteValues
     *
     * @param noteValues Map from field name to field value
     * @param mediaRequests
     * @throws Exception
     */
    public void addMedia(Map<String, String> noteValues, List<MediaRequest> mediaRequests) throws Exception {
        for (MediaRequest media : mediaRequests) {
            // mediaAPI.storeMediaFile() doesn't store as the passed in filename, need to use the returned one
            Optional<byte[]> data = media.getData();
            Optional<String> url = media.getUrl();
            String stored_filename;
            if (data.isPresent()) {
                stored_filename = mediaAPI.storeMediaFile(media.getFilename(), data.get());
            } else if (url.isPresent()) {
                stored_filename = mediaAPI.downloadAndStoreBinaryFile(media.getFilename(), url.get());
            } else {
                throw new Exception("You must provide a \"data\" or \"url\" field. Note that \"path\" is currently not supported on AnkiConnectAndroid.");
            }

            String enclosed_filename = "";
            switch (media.getMediaType()) {
                case AUDIO:
                case VIDEO:
                    enclosed_filename = "[sound:" + stored_filename + "]";
                    break;
                case PICTURE:
                    enclosed_filename = "<img src=\"" + stored_filename + "\">";
                    break;
            }

            for (String field : media.getFields()) {
                String existingValue = noteValues.get(field);

                if (existingValue == null) {
                    noteValues.put(field, enclosed_filename);
                } else {
                    noteValues.put(field, existingValue + enclosed_filename);
                }
            }
        }
    }

    public void updateNoteFields(long note_id, Map<String, String> newFields, ArrayList<MediaRequest> mediaRequests) throws Exception {
        /*
         * updateNoteFields request looks like:
         * id: int,
         * fields: {
         *     field_name: string
         * },
         * audio | video | picture: [
         *     {
         *         data: base64 string,
         *         filename: string,
         *         fields: string[]
         *         + more fields that are currently unsupported
         *      }
         * ]
         *
         * Fields is an incomplete list of fields, and the Anki API expects the the passed in field
         * list to be complete. So, need to get the existing fields and only update them if present
         * in the request. Also need to reverse map each media file back to the field it will be
         * included in and append it enclosed in either <img> or [sound: ]
         */

        String[] modelFieldNames = modelAPI.modelFieldNames(noteAPI.getNoteModelId(note_id));
        String[] originalFields = noteAPI.getNoteFields(note_id);

        // updated fields
        HashMap<String, String> cardFields = new HashMap<>();

        // Get old fields and update values as needed
        for (int i = 0; i < modelFieldNames.length; i++) {
            String fieldName = modelFieldNames[i];

            String newValue = newFields.get(modelFieldNames[i]);
            if (newValue != null) {
                // Update field to new value
                cardFields.put(fieldName, newValue);
                // Ankidroids `getFields` won't return empty fields that are at the end of the array
                // so `originalFields` may potentially contain less fields than `modelFieldNames`
            } else if (originalFields.length >= i + 1) {
                cardFields.put(fieldName, originalFields[i]);
            } else {
                cardFields.put(fieldName, "");
            }
        }

        addMedia(cardFields, mediaRequests);
        noteAPI.updateNoteFields(note_id, cardFields);
    }

    public String storeMediaFile(BinaryFile binaryFile) throws IOException {
        return mediaAPI.storeMediaFile(binaryFile.getFilename(), binaryFile.getData());
    }

    public ArrayList<Long> guiBrowse(String query) {
        // https://github.com/ankidroid/Anki-Android/pull/11899
        Uri webpage = Uri.parse("anki://x-callback-url/browser?search=" + query);
        Intent webIntent = new Intent(Intent.ACTION_VIEW, webpage);
        webIntent.setPackage("com.ichi2.anki");
        // FLAG_ACTIVITY_NEW_TASK is needed in order to display the intent from a different app
        // FLAG_ACTIVITY_CLEAR_TOP and Intent.FLAG_ACTIVITY_TASK_ON_HOME is needed in order to not
        // cause a long chain of activities within Ankidroid
        // (i.e. browser <- word <- browser <- word <- browser <- word)
        // FLAG_ACTIVITY_CLEAR_TOP also allows the browser window to refresh with the new word
        // if AnkiDroid was already on the card browser activity.
        // see: https://stackoverflow.com/a/23874622
        webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        context.startActivity(webIntent);

        // The result doesn't seem to be used by Yomichan at all, so it can be safely ignored.
        // If we want to get the results, calling the findNotes() method will likely cause
        // unwanted delay.
        return new ArrayList<>();
    }
}

