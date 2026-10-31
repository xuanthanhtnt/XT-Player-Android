# XT-Player: thay đổi v0.2.8–v0.3.2 cho bản Android

Cập nhật 31/10/2026 · nguồn: XT-Player (Apple) v0.3.2, commit 61b930f

Từ v0.2.8 đến v0.3.2 (30–31/10/2026), bản Apple có 5 lần phát hành: thư viện và Trakt, chất lượng hình và tiếng, giải mã hi-res, tách nhạc khỏi video nhạc, trình phát nhạc kiểu Roon. Bản Android làm theo các bảng dưới. Phần logic (quy tắc, regex, hằng số, chuỗi tiếng Việt) chép 1:1 từ file Swift được chỉ ra; chỉ lớp phát (mpv → Media3) phải viết riêng.

## Các bản đã phát hành

| Phiên bản | Ngày | Nội dung chính |
| --- | --- | --- |
| v0.3.2 | 31/10/2026 | Đổi cài đặt âm thanh, hình khi đang phát có hiệu lực ngay (đèn chất lượng đổi theo). Now Playing luôn thoát được: nút "Thu nhỏ", Esc, kéo xuống trên iPhone. Menu xử lý âm thanh cạnh nút hàng chờ. |
| v0.3.1 | 31/10/2026 | Trình phát nhạc kiểu Roon: thanh phát dưới cùng, Now Playing, hàng chờ, phát liền mạch, shuffle, repeat, đèn signal path. Trang album có thời lượng và chất lượng thật. Mục Nghệ sĩ, ReplayGain, phát nền trên iOS/tvOS. Nhạc không vào "Đang xem dở". |
| v0.3.0 | 31/10/2026 | Mỗi thư mục thư viện có loại: Phim & TV, Video ca nhạc, Nhạc. Video ca nhạc không tra TMDb/Trakt. Album theo thư mục, mục Nhạc mới, trình duyệt NAS tách Video và Nhạc. |
| v0.2.9 | 31/10/2026 | FFmpeg build lại: DSD, WavPack, MLP, TAK, Musepack, DTS/TrueHD thô, spdif (bitstream HDMI), VVC, thêm định dạng phụ đề. Chế độ đêm dùng dynaudnorm, tăng thoại dùng dialoguenhance. Hi-res theo sample rate của file. Trình cập nhật chỉ xét tag `v*`. |
| v0.2.8 | 30/10/2026 | Preset chất lượng hình, làm mượt chuyển động, công tắc giải mã phần cứng, tăng thoại, chế độ đêm, khớp sample rate. Sửa scrobble Trakt, đồng bộ định kỳ. "Chưa xem" chỉ còn mục chưa từng phát. Nút Cài đặt cuối sidebar. |

## Thư viện, Trakt, cập nhật

Ba mục đầu tiên sửa lỗi mà bản Android hiện cũng có (quy tắc "đã xem", scrobble, đồng bộ). Đường dẫn file tính từ `Sources/` của repo XT-Player.

| Tính năng (bản) | Hành vi cần có | Tham chiếu Apple | Trên Android | Ưu tiên | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| Quy tắc "đã xem" và mục Chưa xem (0.2.8) | Đã xem = còn ≤ 60 s cuối hoặc đã qua 90 %. Đã mở = qua 10 s hoặc đã xem. "Chưa xem" chỉ liệt kê mục chưa mở. | `Services/LocalDatabase/MediaEntry.swift` (`isWatched`, `isStarted`) | `Entities.kt` hiện chỉ xét 60 s cuối: thêm điều kiện 90 %, thêm `isStarted`, lọc Chưa xem theo `!isStarted`. | P1 | Đã làm |
| Scrobble Trakt chờ thời lượng (0.2.8) | Gửi `start` khi đang phát VÀ đã biết duration (trình phát báo duration sau trạng thái playing). Nếu bỏ lỡ start thì pause/stop sau đó cũng bị bỏ và Trakt không thấy gì. Stop dưới 80 % để lại điểm xem tiếp. | `Modules/UIComponents/PlayerScreen.swift` (`onChange(of: engine.duration > 0)`, `scrobble`) | Kiểm tra `PlayerController` (`traktStarted`, kiểm duration): ExoPlayer cũng có thể có `C.TIME_UNSET` lúc `STATE_READY`; gửi start lại khi duration > 0. | P1 | Đã làm |
| Đồng bộ Trakt tự động (0.2.8) | Đồng bộ khi khởi động; khi app trở lại nếu lần trước quá 5 phút; 3 s sau khi đóng player; sau khi quét thư viện; mỗi 15 phút khi không phát (nếu quá 10 phút). HTTP 429: chờ `Retry-After`, thử lại tối đa 2 lần. Ghép theo tên gốc và chấp nhận thiếu năm. Ghi log. | `Modules/UIComponents/RootView.swift`; `Services/TraktSync/TraktService.swift` (`syncIfStale`, `lastSync`) | `ProcessLifecycleOwner` ON\_START, callback đóng player, kết thúc scan, vòng lặp coroutine 15 phút; interceptor OkHttp cho 429. | P1 | Đã làm |
| Loại thư mục thư viện (0.3.0) | Mỗi thư mục có loại Phim & TV, Video ca nhạc hoặc Nhạc. Đoán từ tên: music video, ca nhạc, video nhạc, mv, concert, live, karaoke → Video ca nhạc; music, nhạc, audio, album, songs, lossless, flac, dsd, sacd, hires → Nhạc; còn lại: đa số file là âm thanh → Nhạc, không thì Phim & TV. Đổi được ở Nguồn ▸ "Loại thư mục". | `Services/LocalDatabase/LibraryModels.swift` (`LibraryFolderKind.guess/of/set`); `Modules/UIComponents/SourcesViews.swift` | Lưu map folderKey → loại trong DataStore (như Apple lưu UserDefaults `library.folderKinds`) để khỏi migration Room. Bỏ dấu tiếng Việt trước khi so từ (`Normalizer` NFD). | P1 | Đã làm |
| Video ca nhạc (0.3.0) | Mỗi file một mục, tên lấy từ tên file, ca sĩ/show = tên thư mục. Không tra TMDb hay Trakt, không scrobble. Ảnh lấy từ một khung hình. Có mục "Video ca nhạc" riêng, không lẫn vào Phim. | `Modules/MetadataScraper/LibraryScanner.swift` (`recogniseMusicVideos`) | Thêm nhánh trong scanner theo loại thư mục; kiểu `LibraryKind.musicVideo`. | P1 | Đã làm |
| Đuôi file âm thanh và NAS tách Video/Nhạc (0.2.9) | Nhận: flac m4a alac wav aif aiff aifc caf w64 dsf dff wv ape tta tak mp3 aac ogg oga opus wma mpc shn mka mp2 ac3 eac3 ec3 dts dtshd thd truehd mlp. Trình duyệt NAS có hai nhóm Video và Nhạc, mỗi nhóm phát thành hàng chờ riêng. | `Modules/MetadataScraper/FilenameParser.swift` (`audioExtensions`); `Modules/NetworkStorage/RemoteFileSystem.swift` (`isAudio`, `isPlayable`) | Chép nguyên tập đuôi file. | P1 | Đã làm |
| Nhận diện qua Trakt khi không có khóa TMDb (0.2.8) | Tên tiếng Việt, poster, diễn viên, tập phim lấy từ Trakt. Mục quét trước đó được tra một lần lúc khởi động; tên Trakt không biết thì không hỏi lại. | `Modules/MetadataScraper/TraktMetadata.swift`; `LibraryScanner.swift` (`identifyUnmatched`, `fillEpisodes`) | Thêm nguồn Trakt vào module metadata: search, translations/vi, people, seasons. | P2 | Chưa làm |
| Trình cập nhật chỉ xét tag `v*` (0.2.9) | Bỏ qua release có tag không bắt đầu bằng `v` (ví dụ `deps-ffmpeg-…`). | `Services/Settings/UpdateChecker.swift` | `UpdateChecker` hiện chỉ lọc draft: thêm `tag_name.startsWith("v")`. | P2 | Đã làm |
| Ảnh lỗi không thử lại trong 1 tuần (0.3.0) | File lấy ảnh thất bại được ghi nhớ, 7 ngày sau mới thử lại. | `LibraryScanner.swift` (cache ảnh lỗi) | Bảng nhỏ trong Room hoặc DataStore: fileKey → thời điểm lỗi. | P3 | Chưa làm |
| Cài đặt ghim cuối thanh điều hướng (0.2.8) | Mục Cài đặt nằm dưới cùng thanh bên, tách khỏi các mục thư viện. | `Modules/UIComponents/RootView.swift` (`SidebarSettingsButton`) | Navigation rail/drawer trên tablet và TV: đặt Cài đặt ở cuối. | P3 | Chưa làm |

## Phát video và hình ảnh

Phần này phụ thuộc trình phát nhiều nhất: mpv/libplacebo trên Apple tự vẽ hình, còn Media3 để MediaCodec và hệ thống scale. Hai mục chỉ có trên mpv được đánh dấu Không áp dụng, trừ khi Android chuyển sang libmpv.

| Tính năng (bản) | Hành vi cần có | Tham chiếu Apple | Trên Android | Ưu tiên | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| Đổi cài đặt có hiệu lực ngay (0.3.2) | Đổi giải mã phần cứng, chất lượng hình, làm mượt, tăng thoại, chế độ đêm, ReplayGain, khớp sample rate trong Cài đặt thì nội dung đang phát đổi theo ngay, không cần mở lại. Đèn chất lượng nhạc cập nhật theo. | `Modules/CorePlayer/MPVPlayerEngine.swift` (`followSettings()`, các hàm `set…`) | Collect Flow từ DataStore trong `PlayerController` và service nhạc; bật/tắt audio processor lúc chạy. | P1 | Đã làm |
| Công tắc giải mã phần cứng (0.2.8) | Cài đặt ▸ "Giải mã phần cứng" (mặc định bật), thêm nút trong bảng Video của player. | `Services/Settings/AppSettings.swift` (`player.hardwareDecoding`); `MPVPlayerEngine.setHardwareDecoding` | Setting đã có. Thiếu nút trong player và áp dụng ngay: dựng lại renderer (ưu tiên FFmpeg extension khi tắt) rồi seek về vị trí cũ. | P2 | Đã làm |
| Thông tin tín hiệu trong bảng Info (0.2.9) | Hiện codec đã giải mã, sample rate, bit depth, số kênh, sample rate của thiết bị ra và thiết bị có lấy mẫu lại hay không; đánh dấu hi-res khi sample rate trên 48 kHz. | `Modules/UIComponents/PlayerControlsOverlay.swift`; `MPVPlayerEngine.swift` (`AudioSignal`) | `AnalyticsListener.onAudioInputFormatChanged` / `player.audioFormat`; thiết bị ra từ `AudioManager.getDevices` và `AudioDeviceInfo.getSampleRates`. | P2 | Chưa làm |
| Làm mượt chuyển động (0.2.8) | Cài đặt "Chuyển động mượt (khử rung phim 24p)", mặc định tắt. Apple dùng `video-sync=display-resample`, `interpolation`, `tscale=oversample`. | `MPVPlayerEngine.setMotionSmoothing` | Không có nội suy khung. Gần nhất: khớp tần số quét bằng `setVideoChangeFrameRateStrategy` / `Surface.setFrameRate` (Android 11+) để hết giật 24p. | P3 | Chưa làm |
| Phụ đề thêm định dạng (0.2.9) | Nhận file phụ đề: srt ass ssa vtt sub sup idx smi sami rt pjs stl jss mpl lrc. | `FilenameParser.swift` (`subtitleExtensions`) | Media3 đọc SRT, ASS/SSA, WebVTT, TTML và PGS/VobSub nằm trong MKV. File `.sup`/`.idx` rời, MicroDVD, SAMI… cần parser riêng: làm sau. | P3 | Chưa làm |
| Preset chất lượng hình (0.2.8) | Cân bằng / Cao / Tối đa. Cao: `scale=ewa_lanczossharp`, `deband`, `hdr-peak-percentile=99.995`, `hdr-contrast-recovery=0.30`. Tối đa: thêm `ewa_lanczos4sharpest` và `cscale`. | `MPVPlayerEngine.swift` (`PictureQuality`) | Chỉ có trên mpv. Có thể thử shader khiến dải màu bằng Media3 `GlEffect`, nhưng tốn pin và không bằng. | P3 | Không áp dụng |
| Codec video mới (0.2.9) | VVC/H.266, Theora, DNxHD, CineForm, JPEG 2000… giải mã bằng phần mềm. | `Packages/MPVKit/README.md` | Extension FFmpeg của Media3 chỉ giải mã âm thanh; video phụ thuộc MediaCodec của máy. Chỉ đạt được nếu dùng libmpv. | P3 | Không áp dụng |

## Âm thanh và hi-res

Các con số dưới đây là giá trị Apple đang dùng; Android nên dùng đúng các giá trị này để hai bản nghe giống nhau. Audio processor của Media3 chỉ chạy trên PCM, nên khi đang bitstream thì tăng thoại, chế độ đêm và ReplayGain tự tắt.

| Tính năng (bản) | Hành vi cần có | Tham chiếu Apple | Trên Android | Ưu tiên | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| Chế độ đêm (0.2.8, 0.2.9) | Nâng đoạn nhỏ, giữ tiếng nổ: `dynaudnorm` khung 250 ms, cửa sổ 31, đỉnh 0.6, tăng tối đa 8×. Bật thêm DRC của AC-3/E-AC-3. Khi downmix, LFE còn 0.5. | `MPVPlayerEngine.swift` (`AudioEnhancement.filter`, `ad-lavc-ac3drc`) | Media3 không có dynaudnorm. Nhanh: `DynamicsProcessing` (Android 9+) gắn `audioSessionId`, compressor + limiter. Sát Apple hơn: `AudioProcessor` tự viết theo thuật toán dynaudnorm với các tham số trên. | P1 | Đã làm (nén bằng DynamicsProcessing thay dynaudnorm, chưa có DRC của AC-3) |
| Tăng thoại (0.2.8, 0.2.9) | Stereo: `dialoguenhance` (original 1, enhance 1.5, voice 3). 5.1/7.1 ra loa hoặc tai nghe: downmix stereo với kênh giữa 1.2, surround 0.35, LFE 0. 5.1/7.1 ra receiver: giữ layout, FL/FR 0.7, sau và bên 0.6, LFE 0.7 (0.5 nếu bật chế độ đêm). | `MPVPlayerEngine.swift` (`AudioEnhancement.filter`) | Nhiều kênh: `ChannelMixingAudioProcessor` + `ChannelMixingMatrix` với đúng các hệ số trên. Stereo: `AudioProcessor` mid/side, tăng dải giọng nói ở phần chung hai kênh. | P1 | Đã làm |
| Giải mã âm thanh hi-res (0.2.9) | Phát được DSD (DSF, DFF, DST), WavPack, APE, TTA, TAK, Musepack, Shorten, MPEG-4 ALS, DVD-Audio MLP, DTS/TrueHD thô. | `Packages/MPVKit/README.md`, `Packages/MPVKit/ffmpeg-build.patch` | Media3 đã đọc FLAC, WAV, MP3, AAC, M4A, Ogg/Opus. Còn lại cần: (1) build lại extension FFmpeg của Media3 với danh sách decoder lấy từ patch; (2) `Extractor` riêng cho DSF/DFF, WavPack, APE (Media3 không có). | P2 | Chưa làm |
| Hi-res theo sample rate của file (0.2.8, 0.2.9) | Thiết bị ra chạy đúng sample rate của file khi có thể (macOS: "Khớp tần số lấy mẫu với DAC / loa ngoài", mặc định tắt). Khi buộc phải lấy mẫu lại: bộ lọc dài (`audio-resample-filter-size=32`, `phase-shift=12`). | `MPVPlayerEngine.swift` (`setMatchSampleRate`, `followSourceSampleRate`); `Modules/CorePlayer/AudioHardware.swift` | Android 14+ với DAC USB: `AudioManager.setPreferredMixerAttributes` + `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT`. Máy cũ hơn: mixer hệ thống luôn lấy mẫu lại, chỉ hiển thị cho đúng. | P2 | Chưa làm |
| Cân bằng âm lượng ReplayGain (0.3.1) | Cài đặt "Cân bằng âm lượng nhạc (ReplayGain)", mặc định tắt. Chế độ album, không chống clip, file không có tag thì gain 0 dB. | `MPVPlayerEngine.setVolumeLeveling` (`replaygain=album`) | Đọc tag `REPLAYGAIN_ALBUM_GAIN` (Vorbis comment, ID3 TXXX) từ `Metadata` của Media3; áp gain bằng `AudioProcessor` (vì `player.volume` chỉ giảm, không tăng được). | P2 | Chưa làm |
| Bitstream HDMI (0.2.9) | AC3, E-AC3, DTS, DTS-HD, TrueHD đi nguyên vẹn ra receiver khi bật "Bitstream TrueHD / DTS-HD / E-AC3 qua HDMI" (mặc định tắt). Apple cần thêm muxer `spdif` mới chạy. | `MPVPlayerEngine.swift` (`bitstreamSupported`) | README Android ghi đã có (Media3 `AudioCapabilities`). Chỉ cần thử lại TrueHD và DTS-HD trên Android TV. | P3 |  |

## Nhạc

Trình phát nhạc trên Apple lấy mẫu từ Roon: nhạc chạy nền trong lúc duyệt thư viện, có thanh phát dưới cùng và màn Now Playing. Trên Android, toàn bộ phần này dựng trên `MediaSessionService` + ExoPlayer playlist; các quy tắc bên dưới chép nguyên.

| Tính năng (bản) | Hành vi cần có | Tham chiếu Apple | Trên Android | Ưu tiên | Trạng thái |
| --- | --- | --- | --- | --- | --- |
| Album theo thư mục (0.3.0) | Mỗi thư mục nhạc là một album; thư mục CD1, CD 2, Disc 3, Disk 1 gộp vào thư mục cha với số đĩa. Nghệ sĩ = thư mục bên trên, hoặc phần trước " - " trong "Nghệ sĩ - Album"; năm trong tên. Số bài từ tên file: "1-03 Tên", "03. Tên", "Nghệ sĩ - 04 - Tên". Bìa: cover, folder, front, album, albumart, albumartsmall cạnh file; không có thì ảnh đầu tiên; không có nữa thì ảnh nhúng trong bài đầu. | `LibraryScanner.swift` (`recogniseAlbums`, `discNumber`, `albumTitle`, `coverNames`); `FilenameParser.swift` (`track()`) | Chép 3 regex của `track()` và quy tắc số đĩa nguyên văn. Ảnh nhúng: `MediaMetadataRetriever.getEmbeddedPicture`. | P1 | Đã làm |
| Mục Nhạc, Video ca nhạc trong điều hướng (0.3.0) | Mục riêng cho Nhạc (album) và Video ca nhạc trên thanh bên/tab; Trang chủ có kệ album. Nhạc không vào "Đang xem dở" và không gửi Trakt. | `RootView.swift` (`AppSection`); `HomeView.swift`; `Modules/UIComponents/MusicViews.swift` (`AlbumsView`, `MusicVideosView`) | Thêm route Compose; lọc nhạc khỏi Continue Watching và khỏi scrobble. | P1 | Đã làm |
| Trang album (0.3.0, 0.3.1) | Danh sách bài theo đĩa, thời lượng từng bài và cả album, chất lượng thật sau lần phát đầu ("FLAC · 96 kHz · 24-bit", "DSD64"), đánh dấu bài đang phát. Nút Phát, Trộn bài; menu Phát tiếp, Thêm vào hàng chờ. | `MusicViews.swift` (`AlbumDetailView`, `TrackRow`, `AlbumQueueActions`); `MediaEntry.swift` (`learnTrack`) | Lưu thời lượng và chuỗi chất lượng vào bảng file khi phát xong lần đầu. | P1 | Đã làm |
| Hàng chờ và phát liền mạch (0.3.1) | Phát từ bài được chọn; Phát tiếp, Thêm vào hàng chờ, nhảy tới bài, xóa, kéo để đổi thứ tự, xóa phần sắp tới. Trộn bài chỉ đảo các bài sau bài đang phát; tắt trộn thì về thứ tự cũ. Lặp: tắt → cả hàng chờ → một bài. Lùi bài: đã qua 3 s hoặc đang ở bài đầu thì phát lại từ đầu. Không khoảng lặng giữa bài, không đếm ngược "Tiếp theo". | `Modules/CorePlayer/MusicPlayer.swift` | `addMediaItem(index + 1, …)`, `moveMediaItem`, `removeMediaItem`; `ShuffleOrder` tự viết để chỉ trộn phần sau; `REPEAT_MODE_OFF/ALL/ONE`. ExoPlayer vốn phát liền mạch trong playlist. | P1 | Đã làm |
| Thanh phát và Now Playing (0.3.1, 0.3.2) | Thanh phát dưới cùng (trên thanh tab ở điện thoại), không che mục Cài đặt. Now Playing nền đặc: bìa trên bản mờ của chính nó, hàng chờ bên cạnh, thanh tua, menu xử lý âm thanh cạnh nút hàng chờ. Luôn thoát được: nút "Thu nhỏ", phím Esc, kéo xuống trên điện thoại. | `Modules/UIComponents/MusicPlayerViews.swift` (`MiniPlayerBar`, `MusicNowPlayingView`, `AudioOptionsMenu`, `QueueList`) | `bottomBar` của Scaffold; thoát Now Playing bằng `BackHandler` (cả nút Back D-pad trên TV), vuốt xuống, nút "Thu nhỏ". | P1 | Một phần: menu xử lý âm thanh có Tăng thoại, Chế độ ban đêm; ReplayGain theo bước 5 |
| Đèn signal path (0.3.1, 0.3.2) | Chưa giải mã → xám "Đang mở…". Codec không lossless → vàng "Nguồn nén (lossy)". Bật tăng thoại, chế độ đêm hoặc ReplayGain → xanh dương "Có xử lý". Không có bước nào → tím "Bit-perfect". Còn lại (lấy mẫu lại, âm lượng khác 100 %, tắt tiếng) → xanh lá "Chất lượng cao". Lossless: FLAC, ALAC, WavPack, APE, TTA, TAK, PCM, DSD, DSD (DST), TrueHD, MLP, WMALOSSLESS. Bấm vào đèn: nguồn → xử lý → thiết bị. DSD hiển thị DSD× = sample rate × 8 / 44 100. | `MusicPlayer.swift` (`SignalPath`, `quality(_:)`); `MusicPlayerViews.swift` (`SignalPathDot/Badge/View`) | Đầu vào: `Format.sampleMimeType`, `sampleRate`, `pcmEncoding` và `AudioDeviceInfo` của thiết bị đang ra. Cập nhật ngay khi đổi cài đặt hoặc đổi thiết bị. | P1 | Đã làm |
| Phát nền và điều khiển khóa màn hình (0.3.1) | Nhạc chạy khi tắt màn hình hoặc chuyển app. Mở một video thì nhạc tạm dừng và video nhận nút điều khiển. | `MusicPlayer.swift` (`suspendForVideo`); `NowPlayingController.swift` (`isMusic`) | `MediaSessionService` foreground + thông báo media; UI nối qua `MediaController`. | P2 | Đã làm |
| Nghệ sĩ (0.3.1) | Lưới nghệ sĩ lấy từ nghệ sĩ album; trang nghệ sĩ liệt kê album của họ. | `MusicViews.swift` (`ArtistsView`, `ArtistCard`, `ArtistDetailView`) | Truy vấn Room theo `artist`. | P2 | Chưa làm |

## Tận dụng lại từ Apple

Mã Swift không chạy được trên Android, nhưng phần tốn công nhất là tìm ra quy tắc, ngưỡng và hệ số đúng, và phần đó đã có sẵn. Android chỉ cần dịch sang Kotlin và viết lại lớp phát cho Media3.

| Phần | Cách tận dụng | Ví dụ |
| --- | --- | --- |
| Quy tắc, regex, hằng số | Chép nguyên văn sang Kotlin, không thiết kế lại. | `FilenameParser.track()`, `LibraryFolderKind.guess`, `isWatched`, `AudioEnhancement.filter`, `SignalPath` |
| Ca kiểm thử dùng chung | Gom các cặp đầu vào → kết quả vào một file JSON; test Swift và test Kotlin cùng đọc, nên hai bản luôn nhận diện giống nhau. | "1-03 Song.flac" → đĩa 1, bài 3; thư mục "Karaoke" → Video ca nhạc |
| Chuỗi tiếng Việt | Lấy nhãn, mô tả từ file Swift đưa vào `strings.xml` để hai bản cùng chữ. | "Thu nhỏ", "Loại thư mục", "Bit-perfect", "Nguồn nén (lossy)" |
| Trakt và TMDb | Cùng endpoint, cùng khóa ghép (tên chuẩn hóa + năm, ưu tiên TMDb id), cùng thứ tự đồng bộ. | `TraktService.swift` (`TraktMedia.key`, `sync`), `TraktMetadata.swift` |
| Danh sách FFmpeg | Dùng danh sách decoder trong patch khi build lại extension FFmpeg của Media3 (script build nhận danh sách decoder). | `Packages/MPVKit/ffmpeg-build.patch` |
| Giao diện | Cùng cấu trúc màn hình và luồng thao tác; Compose viết lại theo ảnh chụp bản Apple. | Thanh phát, Now Playing, trang album |
| Không tận dụng được | Viết lại hoàn toàn. | SwiftUI, SwiftData, `MPVPlayerEngine`, CoreAudio, AVAudioSession |

Kotlin Multiplatform hay Swift chạy trên Android không đáng làm lúc này: phải viết lại chính bản Apple hoặc mang runtime Swift sang Android, tốn hơn nhiều so với chép quy tắc.

Lựa chọn nên cân nhắc cho phần nhạc: dùng libmpv chỉ để phát nhạc (giống Apple cũng có một core mpv riêng chỉ phát tiếng), video vẫn dùng Media3. Khi đó DSD, WavPack, APE, chế độ đêm, tăng thoại, ReplayGain dùng lại nguyên chuỗi tùy chọn và filter trong `MPVPlayerEngine.swift`, khỏi viết extractor và audio processor. Đổi lại phải bọc libmpv thành `SimpleBasePlayer` để nối vào MediaSession, và APK nặng thêm thư viện native.

## Giao việc cho dự án Android

Cách tiết kiệm token nhất là cho phiên Claude bên Android đọc thẳng file Swift được chỉ ra, thay vì tự tìm lại quy tắc từ đầu.

1. Đưa tài liệu này (link hoặc bản Markdown) vào repo Android, ví dụ `docs/parity-v0.3.2.md`.
2. Cho phiên Claude bên Android quyền đọc repo Apple: cùng máy thì thêm thư mục XT-Player vào phiên; khác máy thì đọc `xuanthanhtnt/XT-Player` qua `gh`.
3. Làm theo thứ tự, mỗi bước một PR:
   1. Ba lỗi: quy tắc "đã xem", scrobble, đồng bộ Trakt; kèm lọc tag `v*` của trình cập nhật.
   2. Loại thư mục, đuôi file âm thanh, video ca nhạc, album theo thư mục.
   3. Trình phát nhạc: hàng chờ, thanh phát, Now Playing, phát nền, đèn signal path.
   4. Tăng thoại, chế độ đêm, đổi cài đặt có hiệu lực ngay.
   5. P2, P3: ReplayGain, hi-res USB, giải mã DSD/WavPack/APE, nghệ sĩ.
4. Xong mỗi mục thì đổi cột Trạng thái trong các bảng trên.

Prompt mẫu cho bước đầu:

```
Đọc docs/parity-v0.3.2.md. Repo Apple XT-Player chỉ để đọc, không sửa.
Làm các mục P1 nhóm "Thư viện, Trakt, cập nhật" đúng hành vi trong bảng.
Với mỗi mục: mở file Swift ở cột "Tham chiếu Apple", chép quy tắc, regex,
hằng số sang Kotlin, không thiết kế lại. Viết unit test với các ví dụ của
Apple. Build, chạy test, báo lại mục nào khác Apple và vì sao.
```
