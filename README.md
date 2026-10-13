# TheRepeator

A simple Android app for testing HTTP requests and responses on a phone.

This app is not a full Burp Suite proxy. It does not set up device-wide proxy settings or intercept traffic from other apps automatically. Instead, it gives you a quick way to manually send requests, change parameters, replay them, and inspect what the server returns.

![Logo](app/src/main/res/mipmap-xhdpi/ic_app_logo.png)

---

## What this app does

TheRepeator is mainly a mobile HTTP testing tool. It helps you:

- send custom HTTP requests to an API or web endpoint
- change the method, URL, headers, and body before resending
- replay requests to compare behavior
- inspect status codes, response headers, and body content
- review previous requests in history
- test different values quickly
- decode or transform request/response data

This is useful for checking API behavior, debugging mobile app requests, and testing payloads manually.

---

## What it is not

This app is not a system proxy or MITM tool for capturing traffic from other apps by default.

It does not include a built-in proxy configuration for Android system networking. If you want to inspect traffic from another app, you still need a separate proxy setup outside this app.

In other words, TheRepeator is best used as a manual request tool and replay tester, not as a full traffic-capture proxy like Burp.

---

## Main features

- Repeater: send and resend HTTP requests
- Request editing: modify method, URL, headers, and body
- Response view: inspect the returned data and headers
- History: keep track of previous requests
- Payload testing: try different inputs without rebuilding the request manually
- Decoder tools: work with encoded data such as Base64 and URL formats

---

## F-Droid Publishing & Fastlane Metadata

This application is 100% Free and Open Source Software (FOSS). It contains no closed-source SDKs, tracking libraries, or proprietary Google Play dependencies.

App metadata for F-Droid publishing is provided in Fastlane format under:
- `fastlane/metadata/android/en-US/title.txt`
- `fastlane/metadata/android/en-US/short_description.txt`
- `fastlane/metadata/android/en-US/full_description.txt`
- `fastlane/metadata/android/en-US/changelogs/`

F-Droid build recipe template:
- `metadata/com.therepeator.app.yml`

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/SoahCeruP/TheRepeator.git
cd TheRepeator

# Build debug APK
./gradlew app:assembleDebug
```

---

## Releases

You can find the latest APK in the [Releases](https://github.com/SoahCeruP/TheRepeator/releases) section or on [F-Droid](https://f-droid.org).

---

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.
