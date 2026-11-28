# Dynamic Hyper 🏝️✨

> **Ứng dụng Đảo Động (Dynamic Island) & Giao diện Kính Lỏng (Liquid Glass) thế hệ mới dành riêng cho Xiaomi HyperOS.**  
> *Tác giả: **Trần Minh Lộc** (`tranlocbtr99@gmail.com`)*

[![Platform](https://img.shields.io/badge/Platform-Xiaomi%20HyperOS-blue?style=flat-square&logo=android)](https://github.com/tranlocbtr99/DynamicHyper)
[![Language](https://img.shields.io/badge/Language-Kotlin%20%2F%20Jetpack%20Compose-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/Release-v2.5.0-success?style=flat-square)](https://raw.githubusercontent.com/tranlocbtr99/DynamicHyper/main/release/DynamicHyper-v2.5.0.apk)
[![License](https://img.shields.io/badge/License-Proprietary%20%2F%20All%20Rights%20Reserved-red?style=flat-square)](#-bản-quyền--điều-khoản-sở-hữu-trí-tuệ-license)

---

## 📱 Giới thiệu

**Dynamic Hyper** là ứng dụng cá nhân hóa thanh trạng thái đỉnh cao dành riêng cho các thiết bị chạy **Xiaomi HyperOS** (Xiaomi, Redmi, POCO). Ứng dụng biến nốt ruồi camera thành một **Đảo Động thông minh**, đồng thời mang toàn bộ ngôn ngữ thiết kế **Kính Lỏng (Liquid Glass)** sang trọng, mượt mà lên thiết bị của bạn.

---

## 🚀 Tính năng nổi bật

### 1. 🏝️ Đảo Camera Nằm Im 24/7 (Persistent Idle Island)
- Luôn ôm trọn nốt ruồi camera với hình dạng viên nang tối giản đen tuyền hoặc chấm xanh ngọc tinh tế.
- **Tuyệt đối không trôi, không rung lắc**: Khóa chặt vị trí bằng cấu hình `enableFloat = false`, ưu tiên thấp (`pri = -1`) để không gây gián đoạn các tác vụ khác.
- Tự động hồi sinh tức thì 24/7 ngay cả khi bấm "Xóa tất cả ứng dụng gần đây".

### 2. ⚡ Công suất sạc thời gian thực (Realtime Charging Wattage)
- Tự động nhận diện củ sạc và đo đạc công suất sạc trực tiếp theo thời gian thực (ví dụ: `1.2W`, `33W`, `67W`, `90W`, `120W`).
- Hiển thị phần trăm pin và dự tính thời gian sạc đầy (`Time to Full`).

### 3. 🌤️ Viên nang thời tiết trực tiếp (Live Dynamic Weather)
- Tích hợp widget động cập nhật nhiệt độ thực tế và tình trạng thời tiết (Nắng, Mây, Mưa) trực tiếp trên đảo.
- Phông chữ đậm, số to rõ nét, dễ nhìn khi liếc nhanh màn hình.

### 4. 💬 Thông báo nổi & Phím tắt trả lời nhanh (Quick Reply)
- Đón đầu mọi thông báo từ Zalo, Messenger, Telegram, SMS... bung to thành đảo nổi kính lỏng.
- Hỗ trợ nhập liệu và gửi tin nhắn phản hồi trực tiếp ngay trên đảo mà không cần mở ứng dụng.
- Tự động thu gọn êm ái về lại nốt ruồi camera sau khi hoàn tất.

### 5. 🛡️ Chặn cuộc gọi hiểu nhầm & Quản lý ứng dụng
- Phân biệt chính xác giữa cuộc gọi thoại thực tế và tin nhắn chat thông thường.
- Danh sách ứng dụng thông minh: bật/tắt quyền nhận thông báo nổi theo từng app riêng biệt.

### 6. 🪶 Siêu nhẹ & Tối ưu dung lượng
- Dung lượng APK chỉ khoảng **9.6 MB** nhờ tối ưu hóa tài nguyên R8 và ProGuard chuyên sâu.
- Hiệu suất mượt mà 120Hz, không ngốn pin, không sinh nhiệt ngầm.
- Tích hợp kiểm tra và cập nhật phiên bản mới OTA tự động ngay trong ứng dụng.

---

## 📥 Tải về & Cài đặt

Bản phát hành chính thức mới nhất luôn có sẵn tại:
👉 **[Tải APK Dynamic Hyper v2.5.0 (Tải trực tiếp)](https://raw.githubusercontent.com/tranlocbtr99/DynamicHyper/main/release/DynamicHyper-v2.5.0.apk)**

### Các quyền cần thiết:
1. **Truy cập Thông báo (Notification Listener)**: Để nhận và hiển thị thông báo ứng dụng lên đảo.
2. **Quyền Shizuku / ADB**: Để kích hoạt Xiaomi Focus Notification Kit nâng cao trên HyperOS.
3. **Tự khởi chạy (Autostart) & Tiết kiệm pin không giới hạn**: Đảm bảo đảo hoạt động ổn định 24/7.

---

## ⚖️ Bản quyền & Điều khoản Sở hữu Trí tuệ (License)

> **BẢN QUYỀN ĐỘC QUYỀN © 2026 TRẦN MINH LỘC. BẢO LƯU MỌI QUYỀN.**  
> *(PROPRIETARY SOFTWARE - ALL RIGHTS RESERVED)*

- Toàn bộ mã nguồn, thuật toán, kiến trúc phần mềm, tài nguyên hình ảnh và giao diện Kính Lỏng (Liquid Glass) trong dự án này thuộc quyền sở hữu trí tuệ duy nhất của **Trần Minh Lộc**.
- **NGHIÊM CẤM**: Mọi hành vi sao chép, trích xuất mã nguồn, phân phối lại, đảo ngược mã nguồn (reverse engineering), giải mã hoặc tích hợp vào bất kỳ sản phẩm thương mại hoặc phi thương mại nào khác **khi chưa có sự đồng ý chính thức bằng văn bản từ tác giả**.
- Xem chi tiết tại tệp [`LICENSE`](./LICENSE).

---

## 📬 Liên hệ tác giả

- **Tác giả:** Trần Minh Lộc
- **Email:** [tranlocbtr99@gmail.com](mailto:tranlocbtr99@gmail.com)
- **GitHub:** [tranlocbtr99](https://github.com/tranlocbtr99)
- **Hồ sơ cá nhân:** [ltmprofile.pages.dev](https://ltmprofile.pages.dev)
- **Ủng hộ dự án:** Techcombank `4444444790` (TRAN MINH LOC)
