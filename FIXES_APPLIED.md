# ToonStream Aniyomi Extension - Fixes Applied

## Issue
The extension was showing "No shows" when browsing anime. No content was being displayed.

## Root Cause Analysis

### 1. Wrong URL for Popular Anime
- **Problem**: The `popularAnimeRequest()` was pointing to `/home/` which loads content dynamically via JavaScript
- **Impact**: Jsoup parser couldn't find any anime elements since the HTML was empty
- **Fix**: Changed URL from `/home/` to `/category/anime/` which contains static HTML content

### 2. Incorrect CSS Selectors
- **Problem**: Selectors were looking for `article.TPost` and `div.items article` which don't exist in the current website structure
- **Actual Structure**: Website uses `<ul class="post-lst"><li>` containing `<article class="post movies">`
- **Fix**: Updated selectors to match actual HTML structure:
  - Popular: `"ul.post-lst li, article.post.movies"`
  - Search: `"div.result-item article, ul.post-lst li, article.post.movies"`

### 3. Wrong Element Parsing
- **Problem**: Code was looking for `.Title`, `h2`, `h3` for titles and `.Image img` for thumbnails
- **Actual Structure**: Title is in `<img alt="Image [Title]">` and images use direct `src` attribute
- **Fix**: Updated `popularAnimeFromElement()` to:
  - Extract title from img alt attribute (removing "Image " prefix)
  - Fallback to extracting title from URL
  - Check `src` first, then `data-src`, then `data-lazy-src` for thumbnails

### 4. Episode List Selector Issues
- **Problem**: Looking for `ul.episodios li` which doesn't exist
- **Actual Structure**: Episodes are in `<ul id="episode_by_temp"><li>`
- **Fix**: Updated to `"ul#episode_by_temp li, ul.post-lst li article.episodes"`

### 5. Episode Information Extraction
- **Problem**: Episode elements don't have clear text labels
- **Solution**: Extract episode info from URL structure (e.g., `/episode/one-punch-man-1x1/` -> "Episode 1x1")

## Changes Made

### ToonStream.kt

#### 1. Popular Anime Request (Line 40-41)
```kotlin
// OLD:
override fun popularAnimeRequest(page: Int): Request =
    GET(if (page == 1) "$baseUrl/home/" else "$baseUrl/home/page/$page/", headers)

// NEW:
override fun popularAnimeRequest(page: Int): Request =
    GET(if (page == 1) "$baseUrl/category/anime/" else "$baseUrl/category/anime/page/$page/", headers)
```

#### 2. Popular Anime Selector (Line 43-44)
```kotlin
// OLD:
override fun popularAnimeSelector() =
    "article.TPost, div.items article, div.post-cards article"

// NEW:
override fun popularAnimeSelector() =
    "ul.post-lst li, article.post.movies"
```

#### 3. Popular Anime Element Parser (Line 46-68)
```kotlin
// Complete rewrite to match actual HTML structure
// - Extract title from img alt attribute
// - Fallback to URL-based title extraction
// - Proper image source attribute handling (src -> data-src -> data-lazy-src)
// - Better link selector matching
```

#### 4. Search Anime Selector (Line 112-113)
```kotlin
// OLD:
override fun searchAnimeSelector() =
    "div.result-item article, article.TPost, div.items article"

// NEW:
override fun searchAnimeSelector() =
    "div.result-item article, ul.post-lst li, article.post.movies"
```

#### 5. Episode List Selector (Line 136-137)
```kotlin
// OLD:
override fun episodeListSelector() =
    "ul.episodios li, div.episodios li, #seasons .se-c .episodios li"

// NEW:
override fun episodeListSelector() =
    "ul#episode_by_temp li, ul.post-lst li article.episodes"
```

#### 6. Episode Element Parser (Line 154-178)
```kotlin
// Enhanced to extract episode info from URL structure
// - Parses format like "one-punch-man-1x1" to "Episode 1x1"
// - Extracts episode number from "1x2" format
// - Better fallback handling
```

### build.gradle

#### Version Bump (Line 4)
```gradle
// OLD:
extVersionCode = 3

// NEW:
extVersionCode = 4
```

## Testing Recommendations

1. **Popular Anime Tab**: Should now display anime from https://toonstream.dad/category/anime/
2. **Latest Updates Tab**: Should display anime (uses same endpoint)
3. **Search**: Should work for search queries
4. **Anime Details**: Should display title, thumbnail, description (if available on page)
5. **Episode List**: Should display episodes with proper numbering
6. **Video Playback**: No changes made to video extraction, should work as before

## Website Structure (As of April 2025)

### Anime Listing Page Structure
```html
<ul class="post-lst rw sm rcl2 rcl3a rcl4b rcl3c rcl4d rcl6e">
    <li id="post-XXX" class="post-XXX series type-series ...">
        <article class="post dfx fcl movies">
            <div class="post-thumbnail or-1">
                <figure>
                    <img loading="lazy" src="[URL]" alt="Image [Title]">
                </figure>
                <span class="watch btn sm">View Serie</span>
                <span class="play fa-play"></span>
            </div>
            <a href="https://toonstream.dad/series/[slug]/" class="lnk-blk"></a>
        </article>
    </li>
</ul>
```

### Episode List Structure
```html
<ul id="episode_by_temp" class="post-lst rw sm rcl2 rcl3a rcl4b rcl3c rcl4d rcl8e eqcl">
    <li>
        <article class="post dfx fcl episodes fa-play-circle lg">
            <div class="post-thumbnail">
                <figure><img loading="lazy" src="[URL]" alt="Image "></figure>
                <span class="play fa-play"></span>
            </div>
            <a href="https://toonstream.dad/episode/[series-slug-1x1]/" class="lnk-blk"></a>
        </article>
    </li>
</ul>
```

## Notes

- Website uses LiteSpeed Cache (visible in HTML comments)
- Some pages may still load content dynamically
- The `/home/` endpoint specifically has empty content in static HTML
- Category pages like `/category/anime/` have proper static HTML content
- Extension now targets the working endpoints

## Version History

- **v3**: Original version with `/home/` endpoint (not working)
- **v4**: Fixed version with `/category/anime/` endpoint and updated selectors

---
*Fixes applied on: April 16, 2025*
*Fixed by: Emergent AI Agent*
