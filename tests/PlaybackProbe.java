import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** A quiet, short PCM source for device-to-device A2DP verification. */
public final class PlaybackProbe {
    public static void main(String[] args) throws Exception {
        int rate = 44100;
        int buffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, buffer * 2,
            AudioTrack.MODE_STREAM);
        try {
            track.setVolume(0.15f);
            track.play();
            short[] samples = new short[8820];
            for (int block = 0; block < 200; block++) {
                for (int i = 0; i < samples.length / 2; i++) {
                    short value = (short) (2000 * Math.sin(2 * Math.PI * 440 * (block * 4410L + i) / rate));
                    samples[2 * i] = value;
                    samples[2 * i + 1] = value;
                }
                if (track.write(samples, 0, samples.length) < 0) throw new IllegalStateException("Audio write failed");
            }
            track.stop();
        } finally {
            track.release();
        }
        System.out.println("PCM playback completed");
    }
}
