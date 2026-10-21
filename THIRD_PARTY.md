# Лицензии и уведомления

Исходный код GDHAPP распространяется под GNU GPL version 3; полный текст — `LICENSE`.
В проекте используются компоненты с собственными лицензиями и уведомлениями:

| Компонент | Лицензия | Текст |
| --- | --- | --- |
| AndroidLibXrayLite | LGPL-3.0 | native/licenses/AndroidLibXrayLite-LICENSE |
| Xray-core | MPL-2.0 | native/licenses/Xray-core-LICENSE |
| sing-box | GPL-3.0-or-later, дополнительные уведомления о названии | native/licenses/sing-box-LICENSE |
| hev-socks5-tunnel | MIT | native/licenses/hev-LICENSE |
| v2fly geoip | CC BY-SA 4.0 | native/licenses/geoip-LICENSE |
| v2fly domain-list-community | MIT | native/licenses/geosite-LICENSE |
| flag-icons 7.3.2 (Panayiotis Lipiridis) | MIT | native/licenses/flag-icons-LICENSE |

GDHAPP — самостоятельный клиент, не официальный продукт этих проектов.
Исходники native-компонентов, соответствующие поставленным версиям, включены
в `native/sources/`, версии и SHA-256 — в `native/artifacts.json`.
Оригинальные файлы лицензий также включены в APK в `assets/licenses/`.

Плоские флаги: https://github.com/lipis/flag-icons, тег v7.3.2, квадратные SVG
из `flags/1x1`, преобразованные в lossless WebP 128×128. Исходный архив:
`native/sources/flag-icons-v7.3.2.zip`, SHA-256
`9d5415b68061026a466fc3586a8cc2940b66b6a1ee9648f7b98bdaa370275042`.
Повторная генерация: `tools/build-flags.cjs` (Node.js + sharp).

Другие зависимости Gradle: AndroidX/Compose/Material3 (Apache-2.0), Kotlin и
kotlinx.coroutines (Apache-2.0), OkHttp (Apache-2.0), Gson (Apache-2.0),
ZXing Android Embedded и ZXing Core (Apache-2.0). Их Maven-координаты и точные
версии перечислены в `app/build.gradle.kts`; исходные уведомления входят
в опубликованные артефакты соответствующих разработчиков.
