package net.mysterria.translator.util;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public final class Throwables {

    private static final int MAX_CAUSE_DEPTH = 16;

    private Throwables() {
    }

    /**
     * Walks the cause chain looking for the first throwable of {@code type}.
     * <p>
     * Provider failures reach the fallback handler wrapped several layers deep
     * ({@code CompletionException} → {@code RuntimeException} → the real cause), so a
     * single {@code getCause()} is not enough to recognise e.g. a rate-limit failure.
     *
     * @return the matching throwable, or {@code null} if the chain contains none
     */
    public static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    /** Strips {@link CompletionException}/{@link ExecutionException} wrappers. */
    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            boolean wrapper = current instanceof CompletionException || current instanceof ExecutionException;
            if (!wrapper || current.getCause() == null || current.getCause() == current) {
                return current;
            }
            current = current.getCause();
        }
        return current;
    }

    /** A short {@code Type: message} description of the root failure, for debug logs. */
    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
