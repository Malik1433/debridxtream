$files = Get-ChildItem -Path "D:\cursor working\debxtrem\app\src\main\java\com\tvonnet\debridxtreamiptv\player\v2" -Filter *.kt
foreach ($file in $files) {
    (Get-Content $file.FullName) -replace 'package com.tvonnet.debridxtreamiptv.player', 'package com.tvonnet.debridxtreamiptv.player.v2' | Set-Content $file.FullName
}

$allFiles = Get-ChildItem -Path "D:\cursor working\debxtrem\app\src\main\java" -Recurse -Filter *.kt
foreach ($file in $allFiles) {
    (Get-Content $file.FullName) -replace 'import com.tvonnet.debridxtreamiptv.player\.', 'import com.tvonnet.debridxtreamiptv.player.v2.' | Set-Content $file.FullName
}

$xmlFiles = Get-ChildItem -Path "D:\cursor working\debxtrem\app\src\main\res" -Recurse -Filter *.xml
foreach ($file in $xmlFiles) {
    (Get-Content $file.FullName) -replace 'com.tvonnet.debridxtreamiptv.player\.', 'com.tvonnet.debridxtreamiptv.player.v2.' | Set-Content $file.FullName
}

$manifest = "D:\cursor working\debxtrem\app\src\main\AndroidManifest.xml"
(Get-Content $manifest) -replace '\.player\.', '.player.v2.' | Set-Content $manifest
