# TheRepeator

A Burp Suite-inspired mobile security toolkit for Android. It's designed for security researchers, bug bounty hunters, developers, and penetration testers who need to inspect, intercept, modify, and replay HTTP traffic directly from their mobile device.

This project aims to bring a Burp-like workflow into a mobile-first interface, with tools for request replay, payload testing, traffic interception, response viewing, and request history analysis.

> This project is intended for legal security testing, research, and development use only.

![Logo](app/src/main/res/mipmap-hdpi/ic_app_logo.png)

---
## Why this app?

Most professional web security tools are desktop-focused, but sometimes mobile workflows are needed for testing, debugging, and analyzing app traffic on the go.

TheRepeator provides a mobile-focused alternative for:
- viewing and analyzing HTTP traffic
- intercepting browser requests
- modifying requests before sending
- replaying requests with different parameters
- automating repeated testing with payloads
- decoding and transforming request/response data
- monitoring WebSocket traffic

## Key Features

### Repeater
- Send HTTP requests manually
- Edit method, URL, headers, and body
- Replay requests for testing and debugging
- Compare request and response behavior quickly

### Intruder
- Automate payload-based testing
- Use custom markers for injection points
- Support payload libraries and reusable attack templates
- Review results with filtering and detailed response inspection

### Proxy Browser
- Built-in browser for request interception
- View traffic in real time
- Intercept requests and inspect request details
- Modify request data before forwarding

### Decoder / Encoder
- Chain multiple transformations
- Support Base64, URL encoding, Hex, Gzip, JWT-related transformations, and custom workflows
- Useful for payload preparation and analysis

### WebSocket Monitor
- View WebSocket traffic
- Send messages manually
- Inspect real-time payload exchange

### Request History
- Save request history
- Search and filter past requests
- Review status codes, hosts, methods, and response summaries
- Narrow traffic to specific endpoints or content types

### Response Viewer
- Inspect response headers and bodies
- View decoded or formatted content
- Review response metadata such as status, size, and timing

---

## Supported Workflow

TheRepeator is designed around the following security testing flow:

1. Open the browser or send a request
2. Inspect captured or intercepted traffic
3. Modify request parameters or headers
4. Repeat the request with adjusted values
5. Use Intruder for repeated payload-based testing
6. Decode or transform data as needed
7. Review results and export important findings

---

## Final Note
TheRepeator is designed to make mobile HTTP testing more practical, readable, and workflow-friendly for security research and debugging.

It aims to combine the spirit of Burp Suite with a fast, mobile-first experience for modern Android testing.


## Releases

You can find the latest stable APK in the [Releases](https://github.com/SoahCeruP/TheRepeator/releases) section.


#### * Contributions are always welcomed
---