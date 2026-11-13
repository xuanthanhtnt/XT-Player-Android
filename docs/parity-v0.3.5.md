# Parity với XT-Player (Apple) v0.3.3 → v0.3.5

Các thay đổi của bản Apple từ v0.3.3 đến v0.3.5 (12–13/11/2026) và việc tương ứng trên Android. Bản Apple chỉ đọc (`ios-reference`, `git show v0.3.5:…`). Tag: v0.3.3 `543f50f`, v0.3.4 `02facdc`, v0.3.5 `b92f88a`. Bản Apple đã thử trên Apple TV 4K thật, trừ chỗ ghi "chưa thử".

Cột Trạng thái: Đã làm · Đã đúng sẵn (Android đã làm như vậy từ trước) · Không áp dụng (chỉ có ở mpv / Apple) · Chưa làm.

## v0.3.3

| Tính năng | Hành vi cần có | Tham chiếu Apple | Trên Android | Trạng thái |
| --- | --- | --- | --- | --- |
| Giữ màn hình sáng khi phát video | Chỉ giữ sáng khi đang phát video. Tạm dừng, xem xong hoặc nghe nhạc thì màn hình tắt theo hẹn giờ thường. | `DisplaySleepBlocker.swift`, `PlayerScreen` `onChange(state)` | `PlayerScreen`: `view.keepScreenOn` theo trạng thái phát (bỏ `FLAG_KEEP_SCREEN_ON` cố định của `PlayerActivity`). | Đã làm |
| Bộ đệm nhạc nhỏ | Lõi nhạc không dùng bộ đệm 1 GiB của phim (64 MiB). | `MPVPlayerEngine` (`demuxer-max-bytes` khi `audioOnly`) | `MusicPlayer` dùng `DefaultLoadControl` mặc định của Media3 (khoảng 13 MB cho nhạc), không dùng bộ đệm lớn của phim. | Đã đúng sẵn |
| Now Playing chỉ gửi khi nhảy | Thông tin vị trí chỉ gửi khi tua, đổi bài, kẹt; không gửi liên tục. | `NowPlayingController.positionChanged` | `MediaSession.Builder.setPeriodicPositionUpdateEnabled(false)` cho phiên nhạc và phiên video; Media3 vẫn gửi khi có bước nhảy. | Đã làm |
| Ảnh bìa lỗi do NAS chưa sẵn sàng | Hai loại lỗi: không có ảnh (nhớ 1 tuần) và tạm thời không đọc được (thử lại lần sau, bỏ qua cả máy chủ đó trong lượt này). | `FrameGrabber.swift` (`Failure.noPicture/.unavailable`), `LibraryScanner.generateMissingArtwork` | `FrameGrabber.Grab` (`Done`, `NoPicture`, `Unavailable`), `ThumbnailStore.recentlyFailed/markFailed` (7 ngày), danh sách máy chủ không với tới trong lượt. Hoàn thành luôn mục P3 "Ảnh lỗi không thử lại trong 1 tuần" của v0.3.2. | Đã làm |
| VP9 / AV1 4K bằng phần cứng | Video VP9/AV1 4K không giải mã bằng CPU khi máy có bộ giải mã phần cứng. | Đăng ký bộ giải mã VideoToolbox bổ sung (riêng Apple) | `codecSelector` đã xếp bộ giải mã phần cứng trước (khi bật "Giải mã phần cứng"); Media3 dùng MediaCodec. | Đã đúng sẵn |
| Video 48 kHz sau video 44,1 kHz đứng hình | Bỏ lệnh mở lại đầu ra âm thanh lúc file đang mở; chỉ đổi đầu ra khi phim đã chạy ≥ 1,5 s mà không có tiếng, có đánh số lượt. | `MPVPlayerEngine` (`ao-reload`, `checkAudioOutput`, `audioCheckGeneration`) | Android không mở lại đầu ra khi đổi tần số và không tự đổi đầu ra; Media3 tự tạo AudioTrack mới theo định dạng. | Không áp dụng |
| Chất lượng hình với 4K | "Cao"/"Tối đa" quá nặng: trên 1440p tự dùng "Cân bằng". | `effectivePictureQuality` | Android không có preset hình (mục "Preset chất lượng hình" của v0.3.2 là Không áp dụng). | Không áp dụng |
| Thanh điều khiển trình phát trên TV | Tự ẩn sau 8 s, đếm lại mỗi lần bấm. Hiện lại thì focus vào Phát/Dừng. Dòng bảng Thông tin nhận focus để cuộn. Bảng chỉ có tiêu đề (bốn nút nằm dưới thanh). Chữ nút một dòng. | `PlayerControlsOverlay.swift`, `PlayerScreen.swift` (`autoHideDelay`, `FocusableRow`) | `PlayerScreen`: 8 s trên TV, mỗi lần bấm đếm lại; nút Âm thanh / Phụ đề / Video / Thông tin dưới thanh (thay nút ⚙︎); bảng chỉ có tiêu đề và focus vào dòng đầu; dòng Thông tin có nền khi focus; nhãn một dòng. Focus về Phát khi thanh hiện lại đã có từ trước. | Đã làm |

## v0.3.4

| Tính năng | Hành vi cần có | Tham chiếu Apple | Trên Android | Trạng thái |
| --- | --- | --- | --- | --- |
| Ảnh tự hiện khi vừa tạo xong | Ô ảnh hiện ảnh ngay khi file thumbnail được ghi; lỗi "không có file" không bị nhớ. | `ThumbnailStore.announce`, `ArtworkLoader` | `ThumbnailStore.written` tăng sau mỗi lần ghi; `ArtworkImage` gửi lại yêu cầu cho ảnh local đang thiếu (Coil không cache lỗi). | Đã làm |
| Lượt tạo ảnh đầu tiên | Lúc mở app, ảnh không cần Trakt (album, video ca nhạc, ảnh local bị mất) tạo song song với Trakt, mục mới thêm trước; entry trỏ tới ảnh đã mất cũng tạo lại. | `LibraryScanner.generateMissingArtwork(firstPass:)`, `RootView` `.task` | `generateMissingArtwork(firstPass = true)` chạy cùng `trakt.sync()`, sắp mới nhất trước; lượt đầy đủ chờ lượt trước xong; entry dùng `isUsable` thay vì `== null`. | Đã làm |
| Menu ẩn trên TV | Trang phủ kín màn hình. Trái ở mép trang hoặc Back ở gốc trang: menu (≈400) trượt vào, trang co lại neo phải, bo 40, tối đi, bị vô hiệu. Đi trong menu thì trang đổi theo. Chọn hoặc phải thì đóng. Back trong menu thì thoát app. | `TVShell.swift` | `ui/TvShell.kt`: menu 200 dp (400 pt) trượt vào, trang co từ cạnh phải, bo 20 dp, tối 20 %, không nhận focus khi menu mở; đi trong menu thì đổi mục; phải / chọn thì đóng; Back ở gốc mục mở menu, Back trong menu thoát app. Menu gồm Trang chủ, Phim, TV Shows, Nhạc, Video ca nhạc, Nguồn, Cài đặt như Apple (Tìm kiếm, Mới thêm, Chưa xem… không còn trong menu TV). | Đã làm
| Cài đặt là menu cấp 2 trên TV | Các nhóm sổ ra dưới "Cài đặt"; nhóm đang focus hiện bảng (≈760) cạnh menu; lựa chọn liệt kê tại chỗ có ✓. | `SettingsView.swift` (`SettingsGroup`, `choice`), `TVShell.settingsPanel` | `SettingsGroup` + `SettingsGroupContent` (trang Cài đặt điện thoại/tablet dùng chung); bảng 380 dp (760 pt) cạnh menu, trang phía sau mờ và tối 40 %; `ChoiceRow` trên TV liệt kê giá trị tại chỗ có ✓. Phải: vào bảng; Back: về nhóm, rồi thu Cài đặt. Nhóm Hình ảnh trên Android gồm giải mã phần cứng, khớp tần số quét, tunneled. | Đã làm
| Đang phát mới trên TV | Bìa lớn bên trái (thu 0.9 khi dừng), "ĐANG PHÁT · BÀI n/N", nút tròn kính phóng 1,16 khi focus, nền bìa mờ trôi chậm, đổi bài mờ dần, hàng đợi trượt vào từ phải, Back đóng hàng đợi trước. | `MusicPlayerViews.swift` (`TVNowPlaying`, `TVProgress`, `TVRoundButton`) | `TvNowPlaying`: bìa 280 dp, thu 0.9 khi dừng; nút tròn 40 / 52 / 40 và 30 dp phóng 1,16 khi focus; nền bìa nhỏ (w92) blur 20 dp trôi 28 s (1,2↔1,5, ±6°) với gradient; đổi bài mờ dần (Crossfade); hàng đợi 300 dp trượt từ phải, bìa 190 dp, tên 20 sp; Back đóng hàng đợi trước. Chưa tăng độ bão hoà màu nền (`saturation(1.4)`). | Đã làm
| Bố cục TV gọn hơn | Chữ nhỏ một cỡ, poster 190, thẻ ngang 340, lề 70, ảnh đầu trang chủ 640 / tiêu đề 56. | `Theme.swift`, `HomeView.FeaturedHero` | `TvDimens`: lề 35 dp, poster 95 dp, thẻ ngang 170 dp, ảnh đầu 320 dp, tiêu đề 28; chữ TV nhỏ đi (×0,88). Kích thước Apple tính theo pt trên màn 1920, Android TV 960 dp nên chia đôi. | Đã làm

## v0.3.5

| Tính năng | Hành vi cần có | Tham chiếu Apple | Trên Android | Trạng thái |
| --- | --- | --- | --- | --- |
| Focus của menu TV | Mở menu thì focus vào mục đang xem. Menu đóng không nhận focus. Chỉ bấm trái khi focus không đi được nữa mới mở menu. Mở app, đẩy trang mới, ảnh đầu trang đổi đều không mở menu. | `TVShell.swift` (`openMenu`, dải mép trái) | Chặn DPAD_LEFT ở cấp trang (`onKeyEvent`): chỉ mở menu khi `moveFocus(Left)` trả về false; menu đóng không nằm trong cây giao diện; mở menu thì focus vào mục đang xem; mở app hay đẩy trang không mở menu. | Đã làm
| Trang album đang phát | Nút "Phát" thành "Đang phát" (sóng âm), bấm thì mở Đang phát. Ô số thứ tự đủ rộng cho biểu tượng loa. | `MusicViews.swift` (`AlbumDetailView`, `TrackRow.numberWidth`) | `AlbumHeader`: "Đang phát" khi bài đang phát thuộc album, mở Now Playing. Khác Apple theo yêu cầu chủ dự án (0.3.1): khi album đang tạm dừng, nút thành "Phát tiếp" và phát tiếp từ chỗ dừng. Ô số 30 dp với biểu tượng 18 dp không bị cắt trên Android. | Đã làm |
| Tự sửa nhãn chất lượng | Phát ≥ 1,5 s mà thiếu codec hoặc tần số thì đọc lại từ player và ghi log. | `MPVPlayerEngine.repairAudioSignal` | `MusicPlayer.repairSignal`: đọc lại `player.audioFormat`, cập nhật đèn và chất lượng bài, ghi `[audio] audio info read again`. | Đã làm |

## Chỉ có ở Apple (bỏ qua)

Vá CoreAudio của libmpv cho Mac; liên kết AVKit cho Match Content của tvOS; thư mục ảnh chuyển sang Application Support (Android vốn lưu ở `filesDir`); lỗi riêng của AsyncImage / ArtworkLoader của SwiftUI; bộ UI test XCUIRemote.
