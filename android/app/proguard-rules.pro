# Run by the daemon through app_process, by name; nothing in the app refers
# to them.
-keep class li.mercury.bigbrother.catalog.* {
    public static void main(java.lang.String[]);
}
