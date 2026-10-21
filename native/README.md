# Встроенные компоненты NyaWire

`artifacts.json` фиксирует официальные URL, версии и SHA-256 каждого бинарного
компонента и набора данных. APK не загружает ядра при запуске.

- `app/libs/libv2ray.aar`: AndroidLibXrayLite v26.10.17, Xray-core commit
  `92ccc939aaf4f8965f369cf0b142e005aeaa11d5`.
- `app/libs/hev-socks5-tunnel.aar`: HEV 2.19.1 с официальным JNI API `hev.htproxy.TProxyService`.
- `app/src/main/jniLibs/*/libsingbox_exec.so`: неизменённые официальные executable
  sing-box 1.14.3 для Android. Имя `.so` нужно для извлечения Android package manager;
  файл запускается через `ProcessBuilder`, а не загружается через JNI.
- `geoip.dat` и `geosite.dat`: данные v2fly; исходные URL и контрольные суммы
  приведены в реестре. Переименован только `dlc.dat` → `geosite.dat`.

AndroidLibXrayLite и HEV включают четыре ABI. Минимальная версия HEV AAR — API 29.
Начиная с 2.1.2 Xray использует собственный inbound `tun` и `startLoop(config, tunFd)`.
Xray дублирует descriptor, которым продолжает владеть VpnService. SOCKS inbound
остаётся доступным для HTTP-проверок. HEV запускается для sing-box или выбранного
режима совместимости Xray. Встроенные бинарные файлы при этом не менялись.

В 2.2.0 фрагментирование задаётся через `streamSettings.finalmask.tcp`
(`type=fragment`, `packets=tlshello`, `length`, `delay`) закреплённого Xray.
Этот mask предоставляет RawConn/Splice, сохраняя переключение Vision на прямое
копирование TLS. Вариант freedom/dialerProxy отклонён после реального HTTPS-теста,
обнаружившего повреждение TLS-потока. Точные исходники: `infra/conf/transport_finalmask.go`
и `transport/internet/finalmask/fragment/` в архиве Xray-core.
`useLegacyPackaging=true` обеспечивает извлечение executable в `nativeLibraryDir`.
`keepDebugSymbols` предотвращает изменение executable утилитой strip.

Xray не читает `geoip.dat`/`geosite.dat` напрямую из APK assets. Перед
`Libv2ray.initCoreEnv(files/core, ...)` адаптер `BundledRoutingAssets` атомарно
копирует их в `files/core`, проверяет SHA-256 и восстанавливает повреждённые
или отсутствующие файлы. При обновлении наборов данных синхронизируйте размеры
и SHA-256 в этом адаптере с `artifacts.json`. Native-тест с `geoip:private`
и `geosite:google` проверяет загрузку данных и реальную передачу HTTP.

Проверить готовые файлы:

```powershell
powershell -NoProfile -File native/fetch-native.ps1
```

Скачать только отсутствующие файлы с проверкой SHA-256:

```powershell
powershell -NoProfile -File native/fetch-native.ps1 -DownloadMissing
```

Скрипт не заменяет файл с неожиданной контрольной суммой. Сначала исследуйте
локальное изменение, затем восстановите нужный файл осознанно.

`sources/` содержит исходники AndroidLibXrayLite, точного коммита Xray-core,
sing-box, HEV и Java bindings. Архивы не изменены. Инструкции сборки и зависимости
содержатся в README/Makefile/go.mod исходных проектов; для Go-модулей нужен доступ
к их указанным зависимостям. Приложение и адаптеры находятся в `app/src/`.
`licenses/` содержит оригинальные уведомления и тексты лицензий; копии также
поставляются внутри APK в assets/licenses.

Источники: [AndroidLibXrayLite v26.10.17](https://github.com/2dust/AndroidLibXrayLite/releases/tag/v26.10.17),
[Xray-core](https://github.com/XTLS/Xray-core/tree/92ccc939aaf4f8965f369cf0b142e005aeaa11d5),
[sing-box v1.14.3](https://github.com/SagerNet/sing-box/releases/tag/v1.14.3),
[HEV 2.19.1](https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.1),
[v2fly geoip](https://github.com/v2fly/geoip),
[v2fly domain-list-community](https://github.com/v2fly/domain-list-community).
