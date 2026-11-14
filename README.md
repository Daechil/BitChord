<div align="center">

<br/>

<img src="Logo.png" alt="BitChord KO app icon" width="160" />

# BitChord KO

### Claude AI 가사 번역이 들어간 한국어 사용자용 BitChord

<br/>

[![Latest release](https://img.shields.io/github/v/release/Daechil/BitChord?style=for-the-badge&labelColor=0d1117&label=release)](https://github.com/Daechil/BitChord/releases/latest)
[![Based on](https://img.shields.io/badge/based%20on-BitChord%201.9.1-6e40c9?style=for-the-badge&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord)
[![License](https://img.shields.io/badge/license-GPLv3-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)

[**설치**](#설치) · [**추가된 기능**](#추가된-기능) · [**Claude 설정**](#claude-설정) · [**직접 빌드하기**](#직접-빌드하기) · [**크레딧**](#크레딧) · [**English**](#english)

</div>

> [!IMPORTANT]
> 이 저장소는 [BitChord](https://github.com/kushagrasinghx/BitChord) **1.9.1**을 바탕으로 만든 **비공식 개인 포크**예요. 원작자와 관련이 없고, YouTube·Google·Anthropic과도 관련이 없어요.
> 이 포크에서 추가한 기능의 문제는 원본 저장소가 아니라 **이 저장소의 Issues**에 남겨 주세요.

---

## 설치

1. [**Releases**](https://github.com/Daechil/BitChord/releases/latest)에서 `BitChord-KO-1.9.1-ko.N-arm64.apk`를 받아요.
2. 휴대폰에서 APK를 열어 설치해요. 처음이면 브라우저나 파일 앱에 '출처를 알 수 없는 앱 설치'를 허용해야 해요.

| 항목 | 내용 |
|---|---|
| 지원 기기 | **arm64 휴대폰 전용**이에요. 요즘 나오는 안드로이드 폰은 거의 다 arm64예요. TV·워치·32비트 기기용 빌드는 없어요. |
| 공식 앱과 같이 설치 | 패키지 이름이 `com.music.bitchord.ko`라서 **공식 BitChord와 따로 설치돼요.** 로그인·설정·다운로드도 따로예요. |
| 앱 이름 | 홈 화면에 **BitChord KO**로 보여요. |
| 업데이트 | 앱 안의 업데이트 확인이 **이 저장소의 Releases**를 봐요. 새 빌드가 나오면 앱에서 바로 받을 수 있어요. |
| 버전 표기 | `1.9.1-ko.N`이에요. 앞부분은 바탕이 된 원본 버전이고, `N`은 이 포크의 빌드 번호예요. 설정의 버전, 파일 이름, 릴리스 태그가 모두 같아요. |

> [!NOTE]
> 이 포크만의 서명 키로 서명돼요. 그래서 공식 BitChord 위에 덮어서 설치하거나, 그 반대로 업데이트할 수는 없어요.

---

## 추가된 기능

새로 추가한 설정은 모두 **설정 > BitChord KO 기능** 탭에 모여 있어요.

### Claude AI 가사 번역

내 Claude API 키를 넣으면, 가사 번역을 Google 번역 대신 **Claude**가 해요. 노래 전체를 읽고 말투와 흐름을 맞춰서 번역하기 때문에 자연스러워요.

- **모델 선택**: Haiku 5.5(기본, 빠르고 저렴)와 Sonnet 5.5(더 자연스러움, 약 20배 비쌈) 중에서 고를 수 있어요.
- **번역 말투**: 노래에 맞게 Claude가 선택 / 반말 / 존댓말. 한국어로 번역할 때만 적용돼요.
- **번역 방식**: 의역(뜻을 자연스럽게) / 직역(원문에 가깝게).
- **번역한 엔진 표시**
  - Claude가 번역하면 번역 버튼에 작은 ✦ 표시가 붙고, 가사 아래 상태 줄에 `한국어 번역 · Claude`라고 나와요.
  - Claude가 실패해서 Google 번역으로 대신했으면 `Google 번역 (Claude: 시간 초과)`처럼 이유가 함께 나와요.
- **다시 번역**: 번역 버튼을 **길게 누르면** 저장된 번역을 무시하고 새로 받아요.
- **Haiku가 거절하면 Sonnet으로 한 번 더**
  - Haiku는 가끔 가사를 저작권 문제로 보고 번역을 거절해요. 그러면 같은 요청을 Sonnet으로 한 번 더 보내요.
  - 이때 상태 줄은 `Claude Sonnet (Haiku 거절)`로 표시돼요.
  - Sonnet도 거절하면 Google 번역으로 보여줘요.
- **줄 수 맞춤**: 줄마다 따로 칸을 받는 방식으로 요청하기 때문에, 번역이 원문과 한 줄씩 정확히 맞아요.
- **번역 저장**: 번역한 곡은 기기에 저장해 두고, 다시 들을 때는 요청을 보내지 않아요.
- 키가 없거나 Claude 번역을 끄면, 원래 앱처럼 Google 번역을 써요.

### 이 노래 해석

가사 화면의 ✦ 버튼을 누르면 Claude가 노래를 해석해 줘요. 한 줄 요약, 이야기와 감정, 주요 비유와 표현, 눈여겨볼 가사 몇 줄을 보여줘요. 한 번 받은 해석은 저장돼서 다음에는 바로 열려요.

### 원제 찾기 (가사 검색 보완)

한국어로 적힌 외국 곡은 가사를 못 찾는 경우가 많아요. 예를 들어 '발묘 / 나나호시 관현악단'은 원래 '抜錨 / ナナホシ管弦楽団'이에요.
가사를 못 찾으면 YouTube Music과 Claude로 **원래 제목과 아티스트**를 알아낸 뒤, 그 이름으로 가사를 다시 찾아요. 한 번 알아낸 이름은 기억해 둬요.

### Claude 사용량과 진단 기록

- **이번 달 Claude 사용량**: 모델별 입력·출력 토큰 수와 예상 요금(달러)을 보여줘요. 매달 새로 세고, 직접 초기화할 수도 있어요.
- **Claude 진단 기록**
  - 최근 50건의 요청이 어떻게 됐는지 보여줘요: 성공·실패, 실패 이유, 걸린 시간, 줄 수, 토큰 수.
  - **API 키와 가사는 기록하지 않아요.**
  - '기록 복사'로 복사해서 문제를 알릴 때 붙여 넣으면 돼요.

### 다운로드 곡 번역 보관 (기본 켜짐)

- 곡을 다운로드하면 백그라운드에서 가사를 미리 번역해 둬요. Claude가 켜져 있으면 Claude로, 꺼져 있으면 Google로 번역해요.
- 다운로드한 곡의 번역은 캐시가 아닌 **영구 보관함**에 저장돼요(최대 100MB). 캐시를 지워도 남아 있고, **오프라인에서도 번역이 바로 떠요.**
- 앨범을 한꺼번에 받아도 요청이 몰리지 않게 한 곡씩 차례로 번역해요.

### 잠금화면 가사 (기본 꺼짐)

- 곡을 재생 중이거나 일시정지한 상태에서 화면을 켜면, 잠금화면 위에 **큰 실시간 가사**가 떠요. 커버를 흐리게 깐 배경과 재생 버튼, 좋아요 버튼도 함께 나와요.
- 위로 밀면 원래 잠금 해제(PIN·지문·얼굴)로 넘어가요. 잠금이 풀리면 가사 화면은 저절로 닫혀요. 통화 중에는 뜨지 않아요.
- 번역은 플레이어에서 이미 번역해 둔 곡만 보여줘요. 잠금화면이 따로 번역을 요청하지는 않아요.
- **'다른 앱 위에 표시' 권한이 필요해요.** 설정에서 켜면 권한 화면으로 안내해요.

> [!NOTE]
> 일부 제조사(특히 중국 제조사) 휴대폰은 잠금화면 위에 앱을 띄우는 것을 따로 막아요. 이런 기기에서는 잠금화면 가사가 뜨지 않을 수 있어요.

### 이어폰 연결 시 이어서 재생 (기본 켜짐)

블루투스나 유선 이어폰을 연결하면, **30분 안에** 일시정지했던 곡을 다시 재생해요.

---

## Claude 설정

### 1. API 키 받기

1. [console.anthropic.com](https://console.anthropic.com)에 가입하고 로그인해요.
2. **Billing**에서 크레딧을 충전해요. 크레딧이 없으면 키가 동작하지 않아요.
3. **API Keys**에서 키를 만들어요. `sk-ant-`로 시작하는 문자열이에요.

> [!WARNING]
> API 키는 비밀번호와 같아요. 다른 사람에게 보내거나 공개된 곳에 올리지 마세요.

### 2. 앱에 넣기

**설정 > BitChord KO 기능 > Claude AI > AI 가사 번역(Claude)**을 누르고 키를 붙여 넣은 뒤 켜요.
키를 넣으면 모델·번역 말투·번역 방식·사용량·진단 기록 메뉴가 나타나요.

### 3. 요금

요금은 **내 Anthropic 계정에 사용한 만큼** 청구돼요. 실제 사용 기록 기준으로 대략 이 정도예요.

| 모델 | 100만 토큰당 (입력 / 출력) | 노래 한 곡 번역 |
|---|---|---|
| Haiku 5.5 (기본) | $0.10 / $0.50 | 약 $0.001~0.002 (1~3원) |
| Sonnet 5.5 | $2 / $10 | 약 $0.02 (25~30원) |

- 같은 곡은 저장된 번역을 쓰기 때문에 다시 요금이 들지 않아요.
- 노래 해석과 원제 찾기는 요청할 때마다 따로 요금이 들어요. 원제 찾기는 아주 적어요.
- 정확한 요금은 [Anthropic 가격표](https://www.anthropic.com/pricing)와 콘솔의 사용 내역을 확인하세요.

### 4. 보내는 정보

- **Anthropic에 보내는 것**: 번역할 가사, 노래 제목과 아티스트(노래 해석·원제 찾기에 사용)뿐이에요.
- **키 보관**: 키는 **이 기기에만** 저장돼요. 설정 내보내기(백업) 파일에는 들어가지 않아요.
- **다른 서버 없음**: 요청은 앱에서 Anthropic API로 바로 가요. 중간에 다른 서버를 거치지 않아요.

---

## 직접 빌드하기

GitHub Actions의 **`Korean build (arm64)`** 워크플로가 `main`에 푸시할 때마다 빌드하고 릴리스를 만들어요.
포크해서 직접 빌드하려면 이렇게 하면 돼요.

1. 이 저장소를 포크하고, 포크의 **Actions**를 켜요.
2. 서명 키를 만들어요. 한 번 만든 키를 계속 써야 업데이트로 덮어서 설치할 수 있어요.
   ```bash
   keytool -genkeypair -storetype PKCS12 -keystore bitchord-ko.p12 \
     -alias bitchordko -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=BitChord KO"
   base64 -w0 bitchord-ko.p12 > bitchord-ko.p12.base64
   ```
3. 포크의 **Settings > Secrets and variables > Actions**에 두 값을 등록해요.
   - `KO_KEYSTORE_BASE64`: `bitchord-ko.p12.base64` 파일의 내용
   - `KO_KEYSTORE_PASSWORD`: 키를 만들 때 정한 비밀번호
4. `main`에 푸시하면 `v1.9.1-ko.<빌드 번호>` 릴리스와 APK가 만들어져요.
   - 앱 안의 업데이트 확인은 빌드한 저장소의 Releases를 자동으로 봐요.
   - 키를 등록하지 않으면 임시 디버그 키로 서명돼요. 이 경우 업데이트할 때마다 지우고 다시 설치해야 해요.

로컬에서 빌드할 때는 이렇게 해요.

```bash
./gradlew :app:assembleProdRelease -Pbitchord.arm64Only=true -Pbitchord.koBuild=1 -Pbitchord.updateRepo=<owner>/<repo>
```

원본 앱 자체의 빌드 방법과 구조는 [원본 저장소](https://github.com/kushagrasinghx/BitChord)와 [ADDITIONAL.md](ADDITIONAL.md)를 참고하세요.

---

## 원본 기능

BitChord 1.9.1의 기능은 모두 그대로 있어요: YouTube Music 검색과 재생, 무손실 오디오, 크로스페이드와 Automix, 오프라인 다운로드, 단어 단위 가사, Spotify·Discord·Last.fm 연동, 비트퍼펙트 USB 출력 등.
자세한 목록은 [원본 README](https://github.com/kushagrasinghx/BitChord#features)를 보세요.

이 포크에서 **바꾸거나 뺀 것**도 있어요.
- 가사 번역에 Claude를 쓸 수 있어요. 원본은 Google 번역만 지원해요.
- 릴리스는 휴대폰 arm64 APK만 만들어요.
- 원본의 다른 빌드 워크플로는 수동 실행으로만 돌아가게 바꿨어요.

---

## 크레딧

- **[kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord)**: 이 포크의 바탕이 된 원본 앱이에요. 원본이 마음에 드셨다면 [원작자를 후원](https://ko-fi.com/kushagrasinghx)해 주세요.
- **[kkorii03/BitChord](https://github.com/kkorii03/BitChord)**: 노래 해석 화면, 이어폰 연결 시 재생, 잠금화면 가사를 이 포크에서 가져와 1.9.1에 맞게 옮겼어요.
- 가사 제공처와 그 밖의 크레딧은 [원본 README](https://github.com/kushagrasinghx/BitChord#features)에 정리돼 있어요.

## 고지 및 라이선스

- BitChord KO는 독립적인 서드파티 클라이언트예요. Google LLC, YouTube Music, Anthropic 등 어떤 회사와도 관련이 없어요.
- 음악 파일을 호스팅하지 않아요. 사용은 각자의 책임이고, 사는 곳의 저작권법과 각 서비스의 약관을 지켜 주세요.
- Claude 기능의 요금은 사용자 본인의 Anthropic 계정에 청구돼요.
- 원본과 같은 **GNU General Public License v3.0 (GPLv3)**을 따라요. 수정한 소스는 모두 이 저장소에 공개돼 있어요. 자세한 내용은 [LICENSE](LICENSE)를 보세요.

---

## English

**BitChord KO** is an unofficial personal fork of [BitChord](https://github.com/kushagrasinghx/BitChord) 1.9.1 for Korean listeners.
It installs **alongside** the official app (package `com.music.bitchord.ko`), ships **arm64 phone APKs only** from [Releases](https://github.com/Daechil/BitChord/releases/latest), and updates itself from this repository.

It adds the following, all under **Settings > BitChord KO features**:

- **Claude lyric translation** with your own Anthropic API key. Upstream offers Google Translate only.
  - Choice of Haiku 5.5 or Sonnet 5.5, speech level (casual/polite Korean) and natural/literal style.
  - The status line names the engine and the reason for any fallback.
  - Long-press the translate button to re-translate.
  - If Haiku declines a song, it is asked again once with Sonnet; Google Translate is used if that fails too.
- **Song meaning**: Claude explains the song.
- **Original-title lookup** for songs written in Hangul, so lyrics can be found under their original names.
- **Usage counter and diagnostics log**. The log never contains the key or the lyrics.
- **Kept translations for downloads**: translated at download time and kept for offline use.
- **Lock-screen lyrics**. Needs "Display over other apps".
- **Resume when headphones connect**.

Charges for Claude go to your own Anthropic account. Licensed under GPLv3, like upstream.
