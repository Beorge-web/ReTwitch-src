package tv.retwitch.mod;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

/**
 * Host of {@link RecordPlayer}, declared by {@code retwitchRecordServicePatch}: an activity of its own, not a
 * dialog over Twitch's, because the settings activity the recordings page lives in can't go picture-in-picture,
 * and leaving it kept the recording playing with nothing on screen.
 */
public final class RecordPlayerActivity extends Activity {

    static final String EXTRA_ID = "tv.retwitch.mod.record";

    private RecordPlayer player;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        player = RecordPlayer.attach(this, getIntent().getStringExtra(EXTRA_ID));
        if (player == null) {
            finish();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (player != null) {
            player.onLeave();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration config) {
        super.onPictureInPictureModeChanged(pip, config);
        if (player != null) {
            player.onPip(pip);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) {
            player.onStarted(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.onStarted(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.close();
            player = null;
        }
    }
}
