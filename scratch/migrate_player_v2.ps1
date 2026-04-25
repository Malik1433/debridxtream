$files = Get-ChildItem -Path "D:\cursor working\debxtrem\app\src\main\java\com\tvonnet\debridxtreamiptv\player\v2" -Filter *.kt
foreach ($file in $files) {
    $content = Get-Content $file.FullName
    $content = $content -replace 'package com.tvonnet.debridxtreamiptv.player\.v2\.v2', 'package com.tvonnet.debridxtreamiptv.player.v2'
    $content = $content -replace 'package com.tvonnet.debridxtreamiptv.player$', 'package com.tvonnet.debridxtreamiptv.player.v2'
    Set-Content -Path $file.FullName -Value $content
}
