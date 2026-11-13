package tv.retwitch.mod;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * The system sound picker for the mention sound. Its result comes to a headless fragment, as with
 * {@link SettingsBackup}, so no {@code onActivityResult} patch is needed.
 */
final class SoundPicker {

    private static final String TAG = "RTSoundPicker";
    private static final String FRAGMENT_TAG = "retwitch_sound_picker";
    private static final int REQ_SOUND = 0x5243;

    /** Called on the main thread with the picked sound's title. */
    private static volatile Runnable onPicked;

    private SoundPicker() {
    }

    static void pick(Context context, Runnable picked) {
        Activity activity = SettingsBackup.activity(context);
        if (activity == null) {
            return;
        }
        onPicked = picked;
        String current = ChatSettings.getMentionSoundUri();
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.isEmpty()
                        ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(current));
        try {
            PickerFragment fragment = new PickerFragment();
            activity.getFragmentManager().beginTransaction().add(fragment, FRAGMENT_TAG).commitNow();
            fragment.startActivityForResult(intent, REQ_SOUND);
        } catch (Throwable t) {
            Log.w(TAG, "picker failed", t);
            Toast.makeText(context, Strings.pick("Нет выбора звуков", "No sound picker"), Toast.LENGTH_SHORT).show();
        }
    }

    /** The picked sound's title, or the system default's. */
    static String title(Context context) {
        try {
            String uri = ChatSettings.getMentionSoundUri();
            Uri sound = uri.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) : Uri.parse(uri);
            android.media.Ringtone tone = RingtoneManager.getRingtone(context, sound);
            return tone != null ? tone.getTitle(context) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Receives the picker result; public with a no-arg constructor so Android can restore it. */
    public static final class PickerFragment extends Fragment {

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            try {
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            } catch (Throwable ignored) {
                // a leftover headless fragment is harmless
            }
            Context app = AppContext.get();
            if (requestCode != REQ_SOUND || resultCode != Activity.RESULT_OK || data == null || app == null) {
                return;
            }
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            // The "Default" entry stays empty: it follows the system's sound if that changes.
            boolean byDefault = uri == null || RingtoneManager.isDefault(uri);
            ChatSettings.setMentionSoundUri(app, byDefault ? "" : uri.toString());
            Runnable done = onPicked;
            onPicked = null;
            if (done != null) {
                done.run();
            }
        }
    }
}
