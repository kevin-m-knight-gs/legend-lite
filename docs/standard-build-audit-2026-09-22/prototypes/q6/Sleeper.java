public final class Sleeper {
    public static void main(String[] a) throws Exception {
        System.out.println("MARK START " + System.currentTimeMillis()
            + " " + ProcessHandle.current().pid()
            + " maxHeapMB=" + (Runtime.getRuntime().maxMemory() >> 20));
        Thread.sleep(3000);
        System.out.println("MARK END   " + System.currentTimeMillis());
    }
}
