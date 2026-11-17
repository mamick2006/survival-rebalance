package survivalrebalance;

/** An anchor that did not match, or any other reason a mod cannot be patched. */
final class Failure extends Exception {
    Failure(String m) { super(m); }
}
