package tv.glass.home;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.service.notification.NotificationListenerService;

import java.util.List;

// What's playing in the background (music, a podcast, the radio), for the home screen and the screensaver.
// Android only shares that with an allowed notification listener, so this service exists. It never reads a
// notification: it asks Android to unbind it as soon as it connects. Home Settings > Now Playing switches the
// permission on and off through the TV's ADB; it starts off. Nothing it sees leaves the TV.
public class NowPlaying extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        if (Build.VERSION.SDK_INT >= 24) requestUnbind();
    }

    static final class Track { String title, artist, app; }

    // The first session that is playing; null when nothing plays or the permission is off.
    static Track current(Context c) {
        if (!Prefs.on(c, "now_playing", false)) return null;
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = m.getActiveSessions(new ComponentName(c, NowPlaying.class));
            for (MediaController mc : list) {
                PlaybackState st = mc.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                MediaMetadata md = mc.getMetadata();
                if (md == null) continue;
                Track t = new Track();
                t.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                t.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (t.artist == null) t.artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                t.app = Picks.label(c, mc.getPackageName());
                if (t.title != null && !t.title.isEmpty()) return t;
            }
        } catch (SecurityException e) {
            // Not allowed (yet): show nothing.
        } catch (Exception ignored) {}
        return null;
    }

    static String line(Track t) {
        StringBuilder b = new StringBuilder("♪  ").append(t.title);
        if (t.artist != null && !t.artist.isEmpty()) b.append("  ·  ").append(t.artist);
        if (t.app != null) b.append("  ·  ").append(t.app);
        return b.toString();
    }
}
