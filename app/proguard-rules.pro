# File Shelf — keep FileProvider paths
-keepclassmembers class * extends androidx.core.content.FileProvider {
    public <init>(...);
}
