# API Contracts - Xtream Codes Integration

## Overview
The application communicates primarily with Xtream Codes based IPTV providers. All requests are handled via a single base URL (user-provided) + `player_api.php`.

**Base Service:** `XtreamApiService`
**Auth:** Username/Password query parameters on every request.

## Authentication

### Login
- **Endpoint:** `GET /player_api.php`
- **Action:** `login` matches implicit behavior when no action is specified but credentials are provided.
- **Parameters:**
  - `username`: String
  - `password`: String
- **Response:** `XtreamLoginResponse` (Contains user info, server info)

## Live TV

### Get Live Categories
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_live_categories`
- **Response:** `List<XtreamCategory>`

### Get Live Streams
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_live_streams`
- **Parameters:**
  - `category_id`: String (Optional)
- **Response:** `List<XtreamStream>`

## VOD (Movies)

### Get VOD Categories
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_vod_categories`
- **Response:** `List<XtreamCategory>`

### Get VOD Streams
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_vod_streams`
- **Parameters:**
  - `category_id`: String (Optional)
- **Response:** `List<XtreamVodInfo>`

## TV Series

### Get Series Categories
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_series_categories`
- **Response:** `List<XtreamCategory>`

### Get Series List
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_series`
- **Parameters:**
  - `category_id`: String (Optional)
- **Response:** `List<XtreamSeriesInfo>`

### Get Series Info (Details)
- **Endpoint:** `GET /player_api.php`
- **Action:** `get_series_info`
- **Parameters:**
  - `series_id`: String
- **Response:** `XtreamSeriesDetailResponse`

## EPG (Electronic Program Guide)

### Get XMLTV EPG
- **Endpoint:** `GET /xmltv.php`
- **Parameters:**
  - `username`: String
  - `password`: String
- **Response:** `ResponseBody` (XML raw stream)
