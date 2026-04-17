# How to Build and Install the Fixed Extension

## Prerequisites
- Android Studio or IntelliJ IDEA with Android SDK
- JDK 11 or higher
- Aniyomi app installed on your Android device

## Building the Extension

### Method 1: Using Android Studio

1. **Clone the main Aniyomi extensions repository:**
   ```bash
   git clone https://github.com/aniyomiorg/aniyomi-extensions.git
   cd aniyomi-extensions
   ```

2. **Copy this extension to the extensions directory:**
   ```bash
   cp -r /path/to/Toonstream-repo src/hi/toonstream/
   ```

3. **Build the extension:**
   ```bash
   ./gradlew assembleRelease
   ```

4. **Find the APK:**
   The built APK will be in: `repo/apk/hi.toonstream-v4.apk`

### Method 2: Building Standalone (Advanced)

If you want to build this extension standalone, you'll need the Aniyomi extension framework:

1. **Set up the extension framework:**
   ```bash
   git clone https://github.com/aniyomiorg/aniyomi-extensions.git
   cd aniyomi-extensions
   ```

2. **Copy this extension:**
   ```bash
   mkdir -p src/hi/toonstream
   cp -r [this-repo]/* src/hi/toonstream/
   ```

3. **Build:**
   ```bash
   ./gradlew :hi:toonstream:assembleRelease
   ```

## Installing the Extension

1. **Transfer the APK** to your Android device

2. **Install via Aniyomi:**
   - Open Aniyomi app
   - Go to Settings → Extensions
   - Tap the "Install from file" option
   - Select the `hi.toonstream-v4.apk` file
   - Grant installation permissions if prompted

3. **Verify Installation:**
   - Go to Browse → Sources
   - You should see "ToonStream" in the list
   - Tap it to browse Hindi anime

## Alternative: Direct Installation (No Build Required)

If you have a pre-built APK:

1. Enable "Install from Unknown Sources" in Android settings
2. Download the APK to your device
3. Tap the APK file to install
4. Open Aniyomi and the extension should appear

## Updating from Previous Version

If you had v3 installed:

1. Go to Settings → Extensions in Aniyomi
2. Find ToonStream (v3)
3. Tap "Uninstall"
4. Install the new v4 APK following the steps above

## Troubleshooting

### Extension Not Showing Up
- Make sure you're using Aniyomi (not Tachiyomi)
- Clear Aniyomi's cache and restart the app
- Reinstall the extension

### Still Showing "No Shows"
- Clear the extension's cache in Aniyomi
- Force stop Aniyomi and restart
- Check your internet connection
- Verify you installed v4 (not v3)

### Build Errors
- Ensure you have the correct JDK version (11+)
- Make sure Android SDK is properly installed
- Run `./gradlew clean` before building again

## Verifying the Fix

After installation:

1. **Open Aniyomi**
2. **Go to Browse → ToonStream**
3. **Check Popular tab** - Should show anime like:
   - One Punch Man
   - Dragon Ball DAIMA
   - My Dress-Up Darling
   - etc.

4. **Try searching** for an anime
5. **Open an anime** and check if episodes are listed
6. **Try playing** an episode

All these should now work correctly!

## Technical Details

**What Changed in v4:**
- Fixed URL endpoints to use `/category/anime/` instead of `/home/`
- Updated CSS selectors to match current website HTML structure
- Improved title and thumbnail extraction
- Enhanced episode parsing from URLs

**Why It Was Broken:**
- ToonStream website changed their HTML structure
- The `/home/` page now loads content dynamically via JavaScript
- Static HTML parsing (what Aniyomi uses) requires actual HTML content
- The `/category/anime/` endpoint has static HTML that can be parsed

## Support

If you encounter issues:

1. Check the `FIXES_APPLIED.md` file for technical details
2. Verify you're using the latest version (v4)
3. Check if ToonStream website is accessible in your browser
4. Report issues on the GitHub repository

---
*Version 4 - Fixed April 16, 2025*
