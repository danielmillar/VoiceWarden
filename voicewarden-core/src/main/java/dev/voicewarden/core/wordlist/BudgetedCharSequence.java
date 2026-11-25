package dev.voicewarden.core.wordlist;

final class BudgetedCharSequence implements CharSequence {

    private static final int CHECK_MASK = 255;

    private final CharSequence delegate;
    private final long deadlineNanos;
    private int ops;

    BudgetedCharSequence(CharSequence delegate, long deadlineNanos) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        if ((++ops & CHECK_MASK) == 0 && System.nanoTime() - deadlineNanos > 0L) {
            throw BudgetExceededException.INSTANCE;
        }
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new BudgetedCharSequence(delegate.subSequence(start, end), deadlineNanos);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    static final class BudgetExceededException extends RuntimeException {
        static final BudgetExceededException INSTANCE = new BudgetExceededException();

        private BudgetExceededException() {
            super("Regex time budget exceeded", null, false, false);
        }
    }
}
