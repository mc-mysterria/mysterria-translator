package net.mysterria.translator.util.throwable;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class Throwables {

    private static final int MAX_CAUSE_DEPTH = 16;

    public static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    public static String describe(Throwable throwable) {
        Throwable root = throwable;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            boolean wrapper = root instanceof CompletionException || root instanceof ExecutionException;
            if (!wrapper || root.getCause() == null || root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }
}
