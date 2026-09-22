package pl.paweljarczak.cdafreeplayer;
import java.net.HttpURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
public final class RequestToken {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private HttpURLConnection connection;

    public synchronized void cancel() {
        cancelled.set(true);
        if (connection != null) connection.disconnect();
    }

    public boolean isCancelled() { return cancelled.get(); }

    public synchronized void attach(HttpURLConnection value) throws InterruptedException {
        if (cancelled.get()) { value.disconnect(); throw new InterruptedException(); }
        connection = value;
    }

    public synchronized void detach(HttpURLConnection value) {
        if (connection == value) connection = null;
    }
}
