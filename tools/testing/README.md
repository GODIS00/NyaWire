# Локальная проверка Android TUN

В 2.8.0 `RefreshAndGestureTest` проверяет закрытие настоящего зависшего HTTPS
кнопкой Android «Назад», отмену во время чтения тела ответа, освобождение вызова
и mutex для повторного обновления. Проверяются сохранение кеша, пропуск запуска
без сети, пинг всех скрытых фильтром и свёрнутых серверов и лимит параллельности.
Настоящие touch-события проверяют отсутствие действия до отпускания пальца,
короткие жесты, ACTION_CANCEL, возврат карточки, удаление и вертикальную прокрутку.
`ServerActionsTest` проверяет пинг свайпом, копирование через меню, удаление/отмену
и постоянную высоту блока пинга при смене метода.

В 2.7.4 `SoundSettingsTest` декодирует исходный MP3 через MediaCodec и проверяет
RMS/пик PCM, а не только успешный запуск API. Для MediaPlayer проверяются ход
позиции, повторное нажатие, выключение и освобождение. Настоящие кнопки
«Подключить VPN» и «Прослушать звук» проверяются по событиям AudioService.
Прослушивание не запускает VPN. Слышимый результат на физическом телефоне
эмулятор не подтверждает; успешная проверка не означает, что устройство пользователя проверено.

В 2.7.3 аудио проверяется с выключенным звонком (VIBRATE, либо SILENT без вибромотора). Проверка реальной
кнопки VPN импортирует только тестовый RFC5737-профиль, нажимает «Подключить»
и через журнал AudioService подтверждает запуск SoundPool с USAGE_MEDIA.
В finally VPN останавливается, профиль удаляется, настройки и режим звонка
восстанавливаются. На тестовом эмуляторе заранее требуется ACTIVATE_VPN.

В 2.7.2 `SoundSettingsTest` проверяет миграцию старых выключенных эффектов,
сохранение отдельного переключателя звука, SHA-256 исходного MP3, длительность
через MediaMetadataRetriever и реальное декодирование/запуск SoundPool.
UI-проверка открывает функцию через поиск и выключает звук. Эмулятор проверяет
аудио API, а не слышимость на физическом динамике. Для UI-тестов можно временно
выставить системные window/transition/animator scales в 0 и восстановить после
проверки; параметры приложения для трёх постоянных эффектов больше не отключаются.

В 2.7.1 `NativeCoreTest` проверяет один KeepAlive-замер через настоящий VLESS:
первый ответ задерживается на 800 мс, измеряемый — на 90 мс. Проверяется
повторное использование одного соединения, отсутствие холодного запроса в
результате и сохранение реальной задержки ответа. В Disable первый запрос
остаётся измеряемым. Проверки GET/HEAD учитывают один подготовительный запрос
только в KeepAlive. `TouchFeedbackTest` проверяет стандартный тип сигнала,
разрешение VIBRATE и приоритет выключателей приложения/системы. Физическую
вибрацию нужно проверять на телефоне; эмулятор не подтверждает работу мотора.

В 2.7.0 release оптимизируется R8. Проверять нужно именно release APK:
`UsabilityUpdateTest` проверяет таймер сообщения удаления, сохранение активного
профиля, импорт приложений по имени и пакету, сохранение выбора и точный экспорт
в системный буфер. Скриншоты карточки и иконок сохраняются в external cache.
`AppListCodecTest` покрывает списки, одинаковые названия, собственный пакет,
неизвестные названия и ограничение размера. `VpnServiceIntegrationTest` также
проверяет полное имя сервера и отсутствие большого значка в уведомлении.
После оптимизации обязательны `NativeCoreTest` и реальные TUN-проверки всех
трёх путей: Xray native, Xray/HEV и sing-box.

## sing-box REALITY (2.12.4)

`RealityConnectionTest` проверяет VLESS + REALITY + Vision против встроенного
Xray-сервера: профиль Xray, нативный JSON sing-box и отсутствие fingerprint.
Каждый успешный сценарий передаёт HTTP и два больших HTTPS-ответа по одному
соединению с проверкой сертификата. Неверный public key не должен пропускать
данные. До патча этот тест не получал HTTP-ответ; с исправленным ядром проходит.
`NativeCoreTest` дополнительно проверяет версию `1.14.3-nyawire.1`.

В 2.12.5 `HysteriaConnectionTest` также проверяет обе стороны совместимости с
Xray-сервером и подключение по `hy2.fixture.test` из JSON и share link. Локальный
DNS возвращает адрес тестового сервера; его порт непривилегированный. Тест
сначала генерирует обычный production-конфиг, затем меняет только порт DNS для
fixture. Внешние DNS-серверы для этих проверок не нужны. `SingBoxDnsTest`
проверяет настройку DNS, режим IPv4 и сохранение импортированной DNS-политики.
`VpnServiceIntegrationTest` проверяет и включённую проверку при подключении.

`RemoteCoreDiagnosticTest` запускается только с runtime-параметром `profileFile`
из `/data/local/tmp/`. Тест сравнивает GET/HEAD и Default/KeepAlive на двух ядрах,
маскирует данные доступа и не хранит профили в APK. `udpRelayPort` — необязательный
порт локального диагностического UDP-реле. Данные реального сервера не следует
добавлять в assets, исходники и source ZIP.

```powershell
adb -s emulator-5558 shell am instrument -w -r -e class com.gdhapp.client.RealityConnectionTest,com.gdhapp.client.HysteriaConnectionTest,com.gdhapp.client.NativeCoreTest,com.gdhapp.client.RoutingRulesCoreTest com.gdhapp.client.test/androidx.test.runner.AndroidJUnitRunner
```

Используется только отдельный эмулятор Android 10+; тест не добавляет подписки.
`NativeCoreTest` и `RepositoryMigrationTest` запускаются без HTTP-сервера на ПК.
Для `VpnServiceIntegrationTest` нужен Windows-хост и стандартный адрес эмулятора
`10.0.2.2`, который указывает на loopback хоста.

```powershell
# В отдельном скрытом процессе; сохраните PID и остановите его после проверки:
$fixture = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile','-File',"$PWD\tools\testing\host-http-fixture.ps1"
$udpFixture = Start-Process powershell -WindowStyle Hidden -PassThru -ArgumentList '-NoProfile','-File',"$PWD\tools\testing\host-udp-fixture.ps1"
# Укажите serial именно тестового эмулятора:
adb -s emulator-5558 push tools/testing/vpn-probe.sh /data/local/tmp/gdhapp-vpn-probe.sh
adb -s emulator-5558 push tools/testing/vpn-udp-probe.sh /data/local/tmp/gdhapp-vpn-udp-probe.sh
adb -s emulator-5558 shell cmd appops set com.gdhapp.client ACTIVATE_VPN allow
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb -s emulator-5558 install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5558 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5558 shell am instrument -w -r com.gdhapp.client.test/androidx.test.runner.AndroidJUnitRunner
Stop-Process -Id $fixture.Id
Stop-Process -Id $udpFixture.Id
```

На хосте используются только `127.0.0.1:18080` (HTTP) и `127.0.0.1:18081` (UDP). Тест запускает локальный VLESS
сервер, реальный VpnService с выбранным ядром и отправляет HTTP от shell UID,
который, в отличие от UID клиента, попадает в TUN. Успех требует ответа
`NyaWire-TUN-OK` и `NyaWire-UDP-OK`, роста обоих счётчиков и завершения сервиса. Настройки восстанавливаются.
В режиме эмулятора fixture разрешает VPN только тестовому приложению.

## Пропускная способность и REALITY/Vision

`ThroughputBenchmarkTest` пропускается в обычном наборе тестов. Для запуска:

```powershell
adb -s emulator-5558 push tools/testing/vpn-bulk-probe.sh /data/local/tmp/gdhapp-vpn-bulk-probe.sh
adb -s emulator-5558 shell am instrument -w -r -e class com.gdhapp.client.ThroughputBenchmarkTest -e throughput true -e reality true -e https true -e stacks hev,native,hev -e mtus 1500 -e rounds 5 -e sizeMib 16 -e variant reality-https com.gdhapp.client.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5558 pull /sdcard/Android/data/com.gdhapp.client/files/throughput-reality-https.csv
```

HTTPS-источник, VLESS/TCP/REALITY/Vision-сервер и его TLS-target запускаются внутри
эмулятора. Тестовые ключи и сертификаты находятся только в androidTest APK.
Для каждого режима чередуются запросы через SOCKS и от shell UID через настоящий
VPN. Проверяются Content-Length и CRC32 каждого полного файла; ожидание EOF после
передачи и запуск app_process не включаются в время TUN-замера. Настройки и VPN
восстанавливаются после теста. Фикстура не добавляет подписки.
Эмуляторные результаты сравнивают пути внутри клиента и не предсказывают скорость
на удалённом пользовательском сервере. `throughput-fixture.py` можно использовать
отдельно для диагностики сети Windows/эмулятора; итоговый бенчмарк к нему не обращается.
## Проверка настроек и прокрутки (2.2.1)

Установочные APK теперь собираются в release. Для запуска инструментальных
тестов на той же сборке (подпись тестов и приложения должна совпадать):

```powershell
.\gradlew.bat assembleRelease assembleReleaseAndroidTest -PtestBuildType=release
adb -P 5038 -s emulator-5558 install -r app/build/outputs/apk/release/app-release.apk
adb -P 5038 -s emulator-5558 install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb -P 5038 -s emulator-5558 shell am instrument -w com.gdhapp.client.test/androidx.test.runner.AndroidJUnitRunner
```

`SettingsNavigationTest` проверяет порядок пяти разделов, переходы/возврат, поиск и HEX-цвет,
восстановление состояния после пересоздания Activity и изоляцию модели списка
от обновлений трафика. Поиск проходит виртуальное accessibility-дерево Compose;
после переходов ждёт отложенного обновления accessibility.
В 2.3.0 TUN-тесты проверяют отключённый SOCKS: отсутствие порта у native Xray
и отказ неавторизованному клиенту у служебных мостов sing-box и Xray/HEV.

`ScrollPerformanceTest` по умолчанию пропускается. Только на выделенном эмуляторе:

```powershell
adb -P 5038 -s emulator-5558 shell am instrument -w -e class com.gdhapp.client.ScrollPerformanceTest -e scrollPreview true -e variant after221 com.gdhapp.client.test/androidx.test.runner.AndroidJUnitRunner
adb -P 5038 -s emulator-5558 pull /sdcard/Android/data/com.gdhapp.client/cache/scroll-after221.txt
```

Он временно добавляет 100 RFC 5737 тестовых серверов, имитирует подключённый
интерфейс и ежесекундные метрики, выполняет одинаковые свайпы, сохраняет gfxinfo
и скриншот. Настоящий VPN в этом тесте не запускается. В finally удаляются только
созданные группы и восстанавливаются настройки, выбор сервера и состояние сессии.
Измерения на SwiftShader не прогнозируют FPS физического телефона.

## Жесты серверов и пинг (2.6.0)

`ServerActionsTest` проверяет копирование свайпом вправо в настоящий буфер обмена,
удаление свайпом влево и через меню, отмену, сохранение выбора и данных на диске.
Удаление активного профиля проверяется с синтетическим состоянием подключения,
без запуска удалённого VPN. Проверяется стабильность положения кнопки пинга
при переключении всех методов. Отдельная проверка использует изолированное
хранилище для удаления последнего локального и последнего сетевого профиля.
