package pl.paweljarczak.cdafreeplayer;
import java.util.concurrent.atomic.AtomicBoolean;
public final class RequestToken { private final AtomicBoolean cancelled=new AtomicBoolean(false); public void cancel(){cancelled.set(true);} public boolean isCancelled(){return cancelled.get();} }
