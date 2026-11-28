<div align="center">

<br/>

<img src="Logo.png" alt="BitChord KO app icon" width="160" />

# BitChord KO

### Claude AI로 가사를 번역하는 한국어 사용자용 BitChord

<br/>

[![Latest release](https://img.shields.io/github/v/release/Daechil/BitChord?style=for-the-badge&labelColor=0d1117&label=release)](https://github.com/Daechil/BitChord/releases/latest)
[![Beta](https://img.shields.io/github/v/release/Daechil/BitChord?include_prereleases&filter=*-beta&style=for-the-badge&labelColor=0d1117&label=beta&color=orange)](https://github.com/Daechil/BitChord/releases)
[![Based on](https://img.shields.io/badge/based%20on-BitChord%201.10-6e40c9?style=for-the-badge&labelColor=0d1117)](https://github.com/kushagrasinghx/BitChord)
[![License](https://img.shields.io/badge/license-GPLv3-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)

[**설치**](#설치) · [**최근 업데이트**](#최근-업데이트) · [**기능 한눈에**](#기능-한눈에) · [**자세한 기능**](#자세한-기능) · [**Claude 설정과 요금**](#claude-설정과-요금) · [**직접 빌드**](#직접-빌드하기) · [**English**](#english)

</div>

> [!IMPORTANT]
> 원본 [BitChord](https://github.com/kushagrasinghx/BitChord) **1.10**을 바탕으로 만든 **비공식 개인 포크**예요. 원작자와는 관련이 없고, YouTube·Google·Anthropic과도 관련이 없어요.
> 이 포크에서 추가한 기능에 문제가 있으면 원본 저장소가 아니라 **이 저장소의 [Issues](https://github.com/Daechil/BitChord/issues)** 에 남겨 주세요.

---

## 설치

1. [**Releases**](https://github.com/Daechil/BitChord/releases/latest)에서 `BitChord-KO-<버전>-arm64.apk`를 받아요.
2. 휴대폰에서 APK를 열어 설치해요. 처음 설치하면 브라우저나 파일 앱에 '출처를 알 수 없는 앱 설치'를 허용해야 해요.

| 항목 | 내용 |
|---|---|
| 지원 기기 | **arm64 휴대폰만** 지원해요. 요즘 안드로이드 폰은 거의 다 arm64예요. TV·워치·32비트 기기용 빌드는 없어요. |
| 공식 앱과 따로 | 패키지 이름이 `com.music.bitchord.ko`라서 공식 BitChord와 **따로 설치돼요**. 로그인·설정·다운로드도 따로 관리돼요. |
| 업데이트 | 앱이 **이 저장소의 Releases**에서 새 버전을 확인해요. 설정 › 기타 › **업데이트 확인**으로 바로 확인할 수도 있어요. |
| 버전 표기 | `1.10-ko.N` 형식이에요. 앞의 `1.10`은 바탕이 된 원본 버전이고, `N`은 이 포크의 빌드 번호예요. |
| 바뀐 내용 | 각 릴리스 설명과 [KO_CHANGELOG.md](KO_CHANGELOG.md)에 적혀 있어요. |

### 정식 버전과 베타 버전

| | 정식 | 베타 |
|---|---|---|
| 버전 이름 | `1.10-ko.101` | `1.10-ko.102-beta` |
| GitHub | Latest 릴리스 | Pre-release |
| 받는 방법 | 기본 | 설정 › 기타 › **베타 버전 받기** 켜기 |

- 새 기능은 **베타에 먼저** 넣고, 써 본 뒤에 정식으로 내요.
- 베타와 정식은 빌드 번호를 같이 써요. 그래서 베타 다음에 나온 정식 버전으로 그대로 업데이트돼요.
- 안드로이드는 번호가 낮은 버전으로 내려가는 설치를 막아요. 베타를 쓰다가 끄면, 그 베타보다 번호가 높은 정식 버전이 나올 때부터 업데이트가 떠요.

> [!NOTE]
> 이 포크만의 서명 키로 서명돼요. 공식 BitChord와 서로 덮어서 설치할 수 없어요.

---

## 최근 업데이트

> [!TIP]
> 정식 **1.10-ko.101**까지 들어간 내용이에요. 자세한 내용은 [Releases](https://github.com/Daechil/BitChord/releases)와 [KO_CHANGELOG.md](KO_CHANGELOG.md)에 있어요.

| | 새로 생긴 것 |
|---|---|
| **Spotify 곡 찾기** | Spotify 재생목록 곡을 YouTube Music에서 찾을 때 다른 가수의 커버나 다른 버전 대신 **원곡**을 찾아요. 영어 제목·로마자 가수 이름으로 적힌 곡도 앨범과 곡 길이로 찾아요. 시험한 25곡에서 엉뚱한 곡이 5곡 → 0곡. |
| **YouTube Music으로 저장** | 보고 있는 Spotify 재생목록을 YouTube Music 재생목록으로 저장해요. 다시 저장하면 새로 추가된 곡만 넣어요. |
| **미리 듣고 직접 고르기** | 못 찾은 곡은 이유가 보이고, 후보를 미리 들어 본 뒤 직접 골라 넣어요. |
| **일본어 후리가나** | 일본어 곡에서 ふ 버튼을 누르면 한자 위에 읽는 법을 작게 달아요. 한글로 달 수도 있어요. |
| **새 곡 위주 라디오** | 라디오·자동 재생에서 최근 7·30·90일 안에 들은 곡을 뒤로 미루거나 빼요. |
| **블루투스 가사** | 차량·스피커 화면의 곡 제목 자리에 지금 부르는 가사 줄을 띄워요. |
| **업데이트 소식** | 업데이트한 뒤 바뀐 점을 큰 화면으로 보여 줘요. 새 기능·개선·수정으로 나눠 색으로 표시해요. |
| **처음 사용 안내** | 처음 설치하면 놓치기 쉬운 기능과 위치를 쪽마다 알려 줘요. 설정 › 기타에서 다시 볼 수 있어요. |
| **한국 서버 함께 듣기** | 함께 듣기가 서울에 있는 한국어판 전용 서버로 연결돼요. 연결이 빨라졌어요. |
| **자동 번역 (Claude)** | 가사 번역을 켜 둔 채 다음 곡으로 넘어가면 Claude가 알아서 번역해요. 같은 언어인 곡은 건너뛰어요. 처음에는 꺼져 있어요. |
| **가사 더 잘 찾기** | 가수 이름이 "노래"나 날짜로 잘못 읽혀 가사를 못 찾던 문제를 고쳤어요. 한국 노래는 영어 이름으로도 찾고, 음절 단위 가사 우선을 켜면 글자가 노래에 맞춰 차오르는 가사를 더 많이 찾아요. |
| **알기 쉬운 안내** | Claude 키가 거부되거나 Spotify를 불러오지 못하면 이유와 할 일을 쉬운 말로 알려 줘요. 플레이어 버튼은 저절로 숨지 않아요. |
| **쓰기 편하게** | 한국어 말투 통일과 단어 단위 줄바꿈, 설정 바로가기와 "자세히", 가사 버튼 첫 사용 안내. |

---

## 기능 한눈에

이 포크에서 추가한 설정은 **설정 › BitChord KO 기능**에 모여 있어요.

| 분야 | 기능 |
|---|---|
| 🌐 **가사 번역** | Claude 번역(Haiku / Sonnet), 말투·방식 선택, 번역 지시문, 번역 보기 유지 |
| 🔤 **발음 표기** | 무료 한글 발음, Claude 발음, 발음과 번역 함께 보기, 일본어 후리가나 |
| 📖 **노래 이해** | 노래 해석, 곡 배경 웹 검색(나무위키·위키백과·VocaDB), 한국 제목 그대로 옮기기 |
| 📦 **미리 번역** | 재생목록·앨범을 반값(Batch)으로 한 번에 번역, 새 곡 자동 번역, 다운로드 곡 번역 보관 |
| 💰 **비용 관리** | 사용량, 월·하루 한도, 원화 표시, 경고 알림, 프롬프트 캐싱, 진단 기록 |
| 🗂 **번역 저장** | 영구 보관함, 번역 상태 보기, 번역 저장소, 백업·복원(zip) |
| 🎵 **다음 곡 목록** | 겹친 곡 정리(수동·자동), 같은 곡으로 묶기, 라디오 중복 거르기, 새 곡 위주 라디오, Spotify 곡 Claude로 찾기 |
| 🔎 **가사 찾기** | 원제 찾기, 엉뚱한 가사 막기, 업로드마다 다른 길이 맞추기 |
| 📱 **화면** | 플로팅 가사, 잠금화면 가사, 블루투스(차량) 가사, 음파 모양 재생 막대 |
| 🛠 **그 밖에** | 이어폰 연결 시 이어서 재생, 업데이트 확인·업데이트 소식, 베타 받기, 재생 멈춤 안내, 비정상 종료 기록 |

원본 BitChord 1.10의 기능도 모두 그대로 있어요. AutoMix, Google Drive 백업, YouTube Music 싱크 가사, 다음 곡 목록 되돌리기와 여러 곡 선택, 하이라이트(Best Part), 연말 결산(Replay) 등이에요.

---

## 자세한 기능

<details open>
<summary><b>🌐 Claude AI 가사 번역</b></summary>

<br/>

내 Anthropic API 키를 넣으면 가사를 Google 번역 대신 **Claude**가 번역해요. 노래 전체를 읽고 말투와 흐름을 맞추기 때문에 번역이 자연스러워요.

- **모델**
  - Haiku 5.5: 기본, 빠르고 저렴해요.
  - Sonnet 5.5: 더 자연스럽지만 약 20배 비싸요.
- **말투**: 노래에 맞게 Claude가 고르기, 친근하게(반말), 정중하게(존댓말) 중에서 골라요.
  - 존댓말과 반말이 나뉘는 언어라면 한국어가 아니어도 적용돼요. 예: 일본어 タメ口/です・ます, 프랑스어 tu/vous.
- **방식**: 의역(자연스럽게)과 직역(원문에 가깝게) 중에서 골라요.
- **번역 지시문**: Claude에게 줄 규칙을 직접 적을 수 있어요(최대 500자). 예: "아티스트 이름은 번역하지 말 것".
- **줄 맞춤**: 줄 번호를 붙여서 받아요. 그래서 번역이 원문과 한 줄씩 정확히 맞아요. 100줄이 넘는 긴 곡도 돼요.
- **번역 엔진 표시**
  - Claude가 번역하면 번역 버튼에 ✦ 표시가 붙고, 상태 줄에 `한국어 번역 · Claude`가 나와요.
  - Google 번역으로 대신했으면 `Google 번역 (Claude: 시간 초과)`처럼 이유도 함께 보여 줘요.
- **다시 번역**: 번역 버튼을 **길게 누르면** 저장된 번역을 무시하고 새로 받아요.
- **Haiku가 거절하면 Sonnet으로 한 번 더**: 가끔 Haiku가 저작권을 이유로 번역을 거절해요. 그러면 Sonnet으로 한 번 더 보내요.
  - Sonnet도 거절하면 Google 번역을 써요.
  - 이 재시도는 설정에서 끌 수 있어요.
- **번역 보기 유지**: 번역을 켜 두면 다음 곡에서도 켜진 채로 나와요. 이때는 이미 저장된 번역만 보여 주기 때문에 **새로 요금이 나가지 않아요**.
- 키가 없거나 Claude 번역을 끄면 원래 앱처럼 Google 번역을 써요.

</details>

<details>
<summary><b>🔤 발음 표기</b></summary>

<br/>

가사 화면의 **발음 버튼**을 누르면 외국어 가사의 발음을 보여 줘요. 발음 버튼과 번역 버튼은 **따로 켜고 끌 수 있어요**. 둘 다 켜면 원문 아래에 발음이 나오고, 그 아래에 번역이 나와요.

| 방식 | 내용 | 요금 |
|---|---|---|
| **무료 한글 발음** (기본) | Google 로마자를 폰에서 한글로 바꿔요. 예: `aishiteru → 아이시테루`. 일본어·중국어를 지원해요. | 없음 |
| **Claude 발음** | 한자 읽는 법까지 가장 정확해요. 번역 언어의 글자로 적어 줘요. | 번역 한 번 정도 |
| **로마자 그대로** | Google 로마자를 그대로 보여 줘요. | 없음 |

- **발음 버튼을 길게 누르면** 어떤 방식을 골랐든 그 곡만 Claude 발음을 받아요.
- Claude 발음은 한 번 받으면 저장돼요. 같은 곡을 다시 볼 때는 요금이 들지 않아요.
- 설정 › 모양 › **번역·발음 글자 크기**에서 크기를 작게 / 보통 / 크게 중에서 골라요.

- **일본어 후리가나**: 가나와 한자가 있는 일본어 곡에서는 발음 버튼 옆에 **ふ 버튼**이 생겨요. 누르면 한자 위에 작은 글씨로 읽는 법이 나와요.
  - 처음 누를 때 Claude(Haiku)에게 받아요. 곡당 약 3~6원이고, 한 번 받은 곡은 저장돼요.
  - 히라가나 대신 **한글로** 달 수도 있어요.

</details>

<details>
<summary><b>📖 노래 해석과 곡 배경</b></summary>

<br/>

- **노래 해석**: 가사 화면의 ✦ 버튼을 누르면 Claude가 노래를 해석해 줘요. 한 줄 요약, 이야기와 감정, 비유와 표현, 눈여겨볼 가사를 보여 줘요. 한 번 받은 해석은 저장돼요.
- **곡 배경 웹 검색** (기본 꺼짐): 켜면 Claude가 곡마다 **한 번만** 나무위키·위키백과·VocaDB를 검색해요.
  - **곡 배경**: 어떤 작품의 주제가인지, 작곡가가 무슨 말을 했는지 등을 찾아요. 팬 해석이면 팬 해석이라고 구분해 줘요.
  - **어려운 표현 해설**: 말장난, 동음이의어, 이중 의미, 가사에 숨긴 제목을 찾아요. 예: 「テトリス」 = 테트리스 + 카사네 테토.
  - **한국에서 쓰는 제목**: 공식 한국어 제목이나 나무위키 문서 이름을 함께 받아 둬요. 번역할 때 가사에 곡 제목이 나오면 그 이름 그대로 옮겨요. 예: 「抜錨」 → **발묘**.
  - **커버곡**: 제목에 원곡자가 붙은 커버(「抜錨 / ナナホシ管弦楽団」)는 원곡으로 조사해요.
  - 위키에 없는 곡은 전체 웹에서 한 번 더 찾아요(끌 수 있어요). 조사 모델은 Haiku가 기본이고, **Sonnet으로 조사**를 켜면 더 꼼꼼해져요.
  - **검색하는 때**: 노래 해석을 열 때, 번역 버튼을 길게 눌러 다시 번역할 때, 미리 번역할 때 검색해요. 번역 버튼을 짧게 누를 때는 검색하지 않아요.
  - **가사는 검색에 보내지 않아요.** 제목·아티스트·앨범만 보내요. 받은 요약에 가사를 인용한 문장이 있으면 앱이 지워요.
  - 웹 검색으로 만든 요약이라 틀릴 수 있어요. 그래서 화면에 "웹 검색 요약 · 틀릴 수 있어요"라고 표시해요.

</details>

<details>
<summary><b>📦 미리 번역 (반값) · 다운로드 곡 번역</b></summary>

<br/>

재생목록이나 앨범의 가사를 한 묶음으로 Claude에 보내 미리 번역해 둬요. Anthropic의 Batch API를 써서 **요금이 일반 번역의 절반**이에요.

- 재생목록·앨범의 ⋯ 메뉴에서 **가사 미리 번역**을 눌러요. 모델과 발음 함께 받기를 고르면 예상 비용이 나와요.
- 결과는 보통 1시간 안에 와요. 길면 24시간까지 걸리고, 끝나면 알림이 와요. Android 16에서는 진행 상황을 실시간 알림으로 보여 줘요.
- 곡이 많으면 10 / 20 / 30곡씩 나눠서 보내요. 앱이 꺼져도 다시 열면 이어서 진행하고, 이미 보낸 곡은 다시 보내지 않아요.
- 이미 번역된 곡, 가사가 없는 곡, 이미 번역 언어로 된 곡은 빼고 보내요.
- **새 곡 자동 미리 번역**: 재생목록을 정해진 주기(기본 24시간)마다 확인해서 새로 추가된 곡만 보내요.
- **미리 번역 기록**에서 작업별 상태, 곡별 결과, 실제 비용을 볼 수 있어요. 거절된 곡만 Sonnet으로 다시 보낼 수도 있어요.
- **다운로드 곡 번역 보관** (기본 켜짐): 곡을 다운로드하면 가사를 미리 번역해서 영구 보관함에 넣어 둬요. 그래서 **오프라인에서도** 번역이 바로 나와요.
- **앱이 스스로 받는 곡**(좋아요 곡 자동 다운로드, 동기화한 재생목록)은 **반값 배치로만, 하루 30곡까지** 번역해요. 곡이 한꺼번에 수백 곡 들어와도 요금이 갑자기 많이 나가지 않아요.

</details>

<details>
<summary><b>💰 비용 관리와 진단 기록</b></summary>

<br/>

- **이번 달 사용량**: 모델별 토큰 수, 캐시 토큰, 웹 검색 횟수와 예상 요금을 보여 줘요.
- **월 한도**: 없음, $0.50, $1, $2, $5, $10, $20 중에서 골라요. 한도에 닿으면 다음 달까지 Google 번역을 써요.
- **하루 한도**: 없음, $0.10, $0.20, $0.50, $1, $2 중에서 골라요. 한도에 닿으면 자정까지 Google 번역을 써요.
- **경고 알림**: 월 한도의 50%, 80%, 90% 중 고른 시점에 알림이 와요.
- **금액 표시**: 달러, 원화, 둘 다 중에서 골라요.
  - 환율은 자동(하루 1번, 유럽중앙은행 또는 한국수출입은행 기준)이나 직접 입력 중에서 골라요.
  - 카드 해외결제 수수료(0~3%)도 원화 금액에 더할 수 있어요.
- **프롬프트 캐싱**: 번역 지시문을 Anthropic 서버가 5분 동안 기억해요. 그래서 이어서 번역하는 곡은 지시문 값을 **Haiku는 10분의 1, Sonnet은 20분의 1**만 내요. 곡당 번역비가 Haiku는 약 8%, Sonnet은 약 14% 줄어요.
- **진단 기록**: 최근 50건의 요청 결과, 실패 이유, 걸린 시간, 토큰 수를 보여 줘요. **API 키와 가사는 기록하지 않아요.**

예상 요금은 앱이 토큰 수로 계산한 값이에요. 실제 청구액과 조금 다를 수 있어요.

</details>

<details>
<summary><b>🗂 번역 저장 · 상태 · 백업</b></summary>

<br/>

- **Claude 번역 항상 보관** (기본 켜짐): 요금을 내고 받은 번역은 캐시가 아니라 지워지지 않는 보관함에 저장해요.
- **번역 상태**
  - 곡 메뉴에서 보면 Claude 번역됨, 다른 설정으로 번역됨, Google 번역만, 번역 없음 중 어디에 해당하는지 알려 줘요.
  - 재생목록·앨범 메뉴에서는 곡별 상태를 보고, **번역 안 된 곡만 미리 번역**을 보낼 수 있어요.
- **번역 저장소**: 번역이 있는 곡을 목록으로 보고 검색할 수 있어요. 용량 확인, 캐시·보관함 비우기, 곡별 삭제, 보관함 한도(100 / 200 / 500MB) 설정도 여기서 해요.
- **번역 백업·복원**: Claude 번역, 발음, 노래 해석, 곡 배경을 zip 파일 하나로 저장하고 다시 불러와요. 휴대폰을 바꿔도 그대로 쓸 수 있어요.
- **안드로이드 자동 백업**: 설정과 번역 보관함은 자동 백업에 들어가요. 음악 파일, AutoMix 모델, 캐시는 빠져서 백업 한도(25MB)를 넘지 않아요.

</details>

<details>
<summary><b>🎵 다음 곡 목록 정리</b></summary>

<br/>

라디오나 자동 재생은 같은 곡의 다른 업로드를 자주 넣어요. 음원, 뮤직비디오, 가사 영상, Topic 채널 같은 것들이에요. 이런 곡을 골라내요.

- **겹친 곡 정리 버튼**: 다음 곡 목록 위에 있어요. 지금 곡부터 최대 200곡을 살펴봐요.
  1. 먼저 앱 규칙과 직접 묶어 둔 곡으로 찾아요. 이 단계는 무료예요.
  2. Claude 키가 있으면 Claude(Haiku)가 **제목·아티스트·길이만 보고** 나머지를 찾아요. 가사는 보내지 않아요.
  3. 찾은 곡 중 체크한 곡만 빼요. 정리한 뒤에 **되돌리기**를 누르면 뺀 곡이 모두 돌아와요.
- **같은 곡으로 보는 것**: 같은 녹음의 다른 업로드예요. 다른 글자로 쓴 제목(「アイドル」과 「Idol」)도 여기에 들어가요.
- **다른 곡으로 보는 것**: 다른 언어판(「怪物」과 영어판 「Monster」), 커버, 라이브, 리믹스, 어쿠스틱, 인스트, 스피드업, TV 사이즈예요.
- **라디오 곡 자동 정리** (기본 꺼짐): 라디오나 자동 재생이 넣은 곡만 자동으로 빼요. 직접 넣은 곡은 건드리지 않아요. 비용은 한 번에 약 1원이에요.
- **같은 곡으로 묶기**: 곡을 길게 눌러 같은 곡의 다른 업로드와 묶어 둘 수 있어요. 묶어 두면 라디오에 그 곡이 다시 들어오지 않아요.
- 플레이리스트에 곡을 넣을 때도 **같은 곡의 다른 버전이 이미 있으면 물어봐요**. 재생목록 메뉴에는 **중복 곡 찾기**가 있어요.

</details>

<details>
<summary><b>🔎 가사 찾기 보완</b></summary>

<br/>

- **원제 찾기**: 한국어로 적힌 외국 곡은 가사를 못 찾는 일이 많아요. 그러면 YouTube Music과 Claude로 원래 제목과 아티스트를 알아낸 뒤 그 이름으로 다시 찾아요. 예: '발묘 / 나나호시 관현악단' → '抜錨 / ナナホシ管弦楽団'.
- **엉뚱한 가사 막기**: 모든 가사 제공처에서 제목·아티스트·길이가 맞는지 확인해요. 다른 녹음의 가사는 쓰지 않아요.
- **업로드마다 길이가 다른 곡**: 제목과 아티스트가 완전히 같으면 6초 차이까지 같은 곡으로 봐요.
- 제목에 `'`가 들어간 곡("Don't Stop Me Now")은 `'`를 빼고 한 번 더 찾아요.
- **로그 복사**에 가사 제공처마다 결과가 한 줄씩 남아요. 가사 내용은 적지 않아요.

</details>

<details>
<summary><b>📻 라디오 · Spotify 곡 찾기</b></summary>

<br/>

- **새 곡 위주 라디오** (기본 꺼짐): 라디오와 자동 재생에서 최근 7·30·90일 안에 들은 곡을 뒤로 미뤄요. 아예 빼도록 할 수도 있어요. 새 곡이 너무 적으면 들은 곡도 섞어요.
- **Spotify 곡을 Claude로 찾기** (기본 켜짐, Claude 키가 있을 때): Spotify 재생목록을 열면 곡마다 YouTube Music 버전을 찾아요.
  - 규칙으로 확실히 같은 곡(같은 가수, 같은 버전)이 나오면 그대로 써요. 비용이 없어요.
  - 다른 가수의 커버나 다른 버전만 나오면 Claude(Haiku)가 검색 결과 중에서 **같은 녹음**을 골라요. 곡당 약 0.1~0.3원이에요.
  - 길이가 12초 넘게 다르면 받아들이지 않아요. 고른 결과는 기억해서 다시 열 때는 묻지 않아요.
  - 영어 제목이나 로마자 가수 이름으로 적힌 곡은 **곡 길이와 앨범**으로 찾아요. YouTube Music에 뮤직비디오로만 있는 곡도 찾아요.
  - 못 찾은 곡은 회색으로 보이고 **이유**가 나와요. 누르면 후보를 **미리 들어 보고 직접 골라** 넣을 수 있어요. **모두 다시 찾기**로 한꺼번에 다시 찾을 수도 있어요.
- **YouTube Music으로 저장**: Spotify 재생목록 화면의 더하기 버튼으로 같은 재생목록을 YouTube Music 계정에 만들어요. 다시 저장하면 새로 추가된 곡만 넣고, 나중에 직접 고른 곡도 따라 들어가요.

| 공개 곡 25개 시험 | 맞음 | 엉뚱한 곡 | 못 찾음 |
|---|---|---|---|
| 규칙만 | 20 | 5 | 0 |
| Claude 사용 | 23 | **0** | 2 |

</details>

<details>
<summary><b>📱 플로팅 가사 · 잠금화면 가사 · 재생 막대</b></summary>

<br/>

- **플로팅 가사** (기본 꺼짐): 다른 앱을 쓰는 동안 화면 위에 작은 막대로 지금 부르는 가사와 번역을 띄워요.
  - 끌어서 옮길 수 있고, 누르면 앱이 열리고, 꾹 누르면 숨겨져요.
  - 고정하면 터치가 아래 앱으로 그대로 전달돼요.
  - 글자 크기, 배경, 표시 내용, 다음 줄 미리 보기, 테두리, 번역 글자 색, 싱크를 미리보기 창에서 맞출 수 있어요.
- **잠금화면 가사** (기본 꺼짐): 화면을 켜면 잠금화면 위에 큰 실시간 가사와 재생 버튼이 떠요. 위로 밀면 원래 잠금 해제 화면으로 넘어가요.
- 두 기능 모두 **'다른 앱 위에 표시' 권한**이 필요해요. 그리고 플레이어에서 이미 번역해 둔 곡의 번역만 보여 줘요. 따로 번역을 요청하지는 않아요.
- **음파 모양 재생 막대** (기본 꺼짐): 재생 막대를 곡의 음파 모양으로 보여 줘요.
- **블루투스 가사** (기본 꺼짐): 차량이나 스피커처럼 곡 제목을 보여 주는 블루투스 기기에서, 제목 자리에 지금 부르는 가사 줄을 띄워요. 이어폰·헤드폰에는 따로 켜야 나와요.

> [!NOTE]
> 일부 제조사 휴대폰은 잠금화면 위에 앱을 띄우는 것을 따로 막아요. 이런 기기에서는 잠금화면 가사가 뜨지 않을 수 있어요.

</details>

<details>
<summary><b>🛠 그 밖에</b></summary>

<br/>

- **이어폰 연결 시 이어서 재생** (기본 켜짐): 블루투스나 유선 이어폰을 연결하면, 30분 안에 일시정지했던 곡을 다시 재생해요.
- **업데이트 확인**: 설정 › 기타에서 새 버전이 있는지 바로 확인해요. 바로 아래에 **베타 버전 받기**가 있어요.
- **재생 멈춤 안내**: 곡이 5번 연달아 시작되지 않아서 재생이 멈추면 이유를 알려 줘요. 그 자리에서 로그를 복사할 수 있어요.
- **비정상 종료 기록**: 앱이 오류로 꺼지면 다음에 열 때 알려 줘요. 기록에는 가사와 API 키가 들어가지 않아요.
- **업데이트 소식**: 업데이트한 뒤 처음 열면 바뀐 점을 큰 화면으로 보여 줘요. 맨 위에 핵심 3가지, 그 아래 새 기능·개선·수정 묶음이 색 글자로 나와요. 베타를 건너뛰었어도 그동안의 내용을 함께 보여 줘요. **다시는 안 보기**로 끌 수 있어요.
- **쓰기 편하게**: 재생 중인 곡을 누르면 처음부터 다시 틀지 않고 플레이어를 열어요. 가사 버튼은 처음 쓸 때 한 번 설명해 줘요. KO 설정 페이지는 위쪽 칩으로 그룹을 바로 찾아가고, 긴 설명은 "자세히"로 펼쳐요.

</details>

---

## Claude 설정과 요금

### 1. API 키 받기

1. [console.anthropic.com](https://console.anthropic.com)에 가입하고 로그인해요.
2. **Billing**에서 크레딧을 충전해요. 크레딧이 없으면 키가 동작하지 않아요.
3. **API Keys**에서 키를 만들어요. 키는 `sk-ant-`로 시작해요.

> [!WARNING]
> API 키는 비밀번호와 같아요. 다른 사람에게 보내거나 공개된 곳에 올리지 마세요.

### 2. 앱에 넣기

설정 › BitChord KO 기능 › **Claude AI** › **AI 가사 번역(Claude)** 을 열고 키를 붙여 넣은 뒤 켜요.
키를 넣으면 KO 기능 화면에 아래 다섯 칸이 나타나요.

| 칸 | 들어 있는 것 |
|---|---|
| **Claude AI** | 번역, 모델, 말투, 방식, 발음·번역 규칙, Sonnet 재시도 |
| **곡 배경** | 웹 검색, 전체 웹에서도 찾기, Sonnet으로 조사 |
| **미리 번역** | 기록, 새 곡 확인 주기, 묶음 크기 |
| **비용과 기록** | 사용량, 월 한도, 금액·한도 설정, 진단 기록 |
| **번역 저장** | 항상 보관, 번역 저장소 |

### 3. 요금

요금은 **내 Anthropic 계정에 쓴 만큼만** 청구돼요. 실제로 써 본 기준으로 대략 이 정도예요.

| 쓰는 곳 | Haiku 5.5 (기본) | Sonnet 5.5 |
|---|---|---|
| 100만 토큰당 (입력 / 출력) | $0.10 / $0.50 | $2 / $10 |
| 노래 한 곡 번역 | 약 1~3원 | 약 20~30원 |
| 미리 번역 (Batch) | 위의 절반 | 위의 절반 |
| 곡 배경 조사 (곡마다 한 번) | 약 20~40원 | 약 70~100원 |
| 겹친 곡 정리 (한 번) | 약 1원 | — |

- 같은 곡은 저장된 번역을 쓰기 때문에 다시 요금이 들지 않아요.
- 노래 해석은 처음 열 때만 요금이 들어요. 원제 찾기는 요금이 아주 적어요.
- 걱정되면 **월 한도**나 **하루 한도**를 정해 두세요.
- 정확한 요금은 [Anthropic 가격표](https://www.anthropic.com/pricing)와 콘솔의 사용 내역에서 확인하세요.

### 4. 보내는 정보와 키 보관

- **Anthropic에 보내는 것**: 번역하거나 발음을 적을 가사, 곡 제목·아티스트·앨범, 다음 곡 목록의 제목·길이만 보내요.
- **키 보관**: 키는 **이 기기에만, 암호화해서** 저장돼요. 설정 내보내기, 번역 백업, Android 자동 백업, Google Drive 백업 어디에도 들어가지 않아요.
- **중간 서버 없음**: 요청은 앱에서 Anthropic API로 바로 가요.

---

## 직접 빌드하기

GitHub Actions의 **`Korean build (arm64)`** 워크플로가 빌드하고 릴리스를 만들어요.

| 브랜치 | 만들어지는 것 |
|---|---|
| `main`에 푸시 | 정식 릴리스 `v1.10-ko.<빌드 번호>` |
| `beta`에 푸시 | 베타 릴리스 `v1.10-ko.<빌드 번호>-beta` (Pre-release) |

릴리스 설명은 [KO_CHANGELOG.md](KO_CHANGELOG.md)에서 그 버전의 항목을 가져와요. `.md` 파일만 바꾼 푸시에서는 앱을 빌드하지 않아요.

포크해서 직접 빌드하려면 이렇게 해요.

1. 이 저장소를 포크하고, 포크의 **Actions**를 켜요.
2. 서명 키를 만들어요. 한 번 만든 키를 계속 써야 업데이트로 덮어서 설치할 수 있어요.
   ```bash
   keytool -genkeypair -storetype PKCS12 -keystore bitchord-ko.p12 \
     -alias bitchordko -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=BitChord KO"
   base64 -w0 bitchord-ko.p12 > bitchord-ko.p12.base64
   ```
3. 포크의 **Settings › Secrets and variables › Actions**에 두 값을 등록해요.
   - `KO_KEYSTORE_BASE64`: `bitchord-ko.p12.base64` 파일의 내용
   - `KO_KEYSTORE_PASSWORD`: 키를 만들 때 정한 비밀번호
4. `main`이나 `beta`에 푸시해요. 앱의 업데이트 확인은 빌드한 저장소의 Releases를 자동으로 봐요.
   - 서명 키가 없으면 릴리스를 올리지 않아요. 다른 키로 서명된 APK는 덮어서 설치할 수 없어서, 다시 설치하다가 번역 보관함을 잃을 수 있기 때문이에요.

로컬에서 빌드할 때는 이렇게 해요.

```bash
./gradlew :app:assembleProdRelease -Pbitchord.arm64Only=true -Pbitchord.koBuild=1 -Pbitchord.updateRepo=<owner>/<repo>
```

원본 앱의 구조와 빌드 방법은 [원본 저장소](https://github.com/kushagrasinghx/BitChord)와 [ADDITIONAL.md](ADDITIONAL.md)를 참고하세요.

---

## 크레딧

- **[kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord)**: 이 포크의 바탕이 된 원본 앱이에요. 마음에 드셨다면 [원작자를 후원](https://ko-fi.com/kushagrasinghx)해 주세요.
- **[kkorii03/BitChord](https://github.com/kkorii03/BitChord)**: 노래 해석 화면, 이어폰 연결 시 재생, 잠금화면 가사를 이 포크에서 가져와 옮겼어요.
- 가사 제공처와 그 밖의 크레딧은 [원본 README](https://github.com/kushagrasinghx/BitChord#features)에 있어요.

## 고지 및 라이선스

- BitChord KO는 독립적인 서드파티 클라이언트예요. Google LLC, YouTube Music, Anthropic 등 어떤 회사와도 관련이 없어요.
- 음악 파일을 호스팅하지 않아요. 사용 책임은 각자에게 있어요. 사는 곳의 저작권법과 각 서비스의 약관을 지켜 주세요.
- Claude 기능의 요금은 사용자 본인의 Anthropic 계정에 청구돼요.
- 원본과 같은 **GNU General Public License v3.0 (GPLv3)** 을 따라요. 수정한 소스는 모두 이 저장소에 공개돼 있어요. 자세한 내용은 [LICENSE](LICENSE)를 보세요.

---

## English

**BitChord KO** is an unofficial personal fork of [BitChord](https://github.com/kushagrasinghx/BitChord) **1.10** for Korean listeners.
It installs **alongside** the official app (package `com.music.bitchord.ko`). It ships **arm64 phone APKs only** from [Releases](https://github.com/Daechil/BitChord/releases/latest), and it updates itself from this repository.

Stable builds are `1.10-ko.N`. Beta builds are `1.10-ko.N-beta`, published as GitHub pre-releases and offered only when **Settings › Other › Get beta versions** is on.

Additions, all under **Settings › BitChord KO features**:

- **Claude lyric translation** with your own Anthropic API key, using Haiku 5.5 or Sonnet 5.5.
  - You choose the speech level and style, and can add your own instructions.
  - If Haiku declines a song, it retries once with Sonnet and falls back to Google Translate.
- **Pronunciation**: free on-device Hangul readings or Claude readings, shown together with the translation.
- **Song meaning** and an optional **song background web search** (Namu Wiki, Wikipedia, VocaDB) covering wordplay, the context of the work and the Korean title. Only the title, artist and album are sent.
- **Batch pre-translation** of playlists and albums at half price, plus kept offline translations for downloads.
  - Songs the app fetches on its own go through batch only, at most 30 a day.
- **Cost controls**: usage view, monthly and daily limits, KRW display, alerts and prompt caching. The diagnostics log never contains the key or the lyrics.
- **Queue tidy**: finds re-uploads of the same recording among the upcoming songs, using rules first and then Claude with titles and lengths only.
  - It keeps other-language versions, covers and remixes. One undo restores everything it removed.
  - An optional auto mode cleans radio additions, and you can link songs as "same song" by hand.
- **Lyrics lookup**: original-title lookup for songs written in Hangul, and stricter matching against wrong lyrics.
- **Floating lyrics**, **lock-screen lyrics**, a waveform seek bar, resume when headphones connect, an update check, a beta channel and crash and stall notices.
- New in beta: **Spotify tracks matched by Claude** to the same recording on YouTube Music (no more covers by others), **Japanese furigana**, **fresh-first radio**, **lyrics on Bluetooth car displays**, and paged release notes shown once after each update.

The API key is stored encrypted on the device only. It is never included in exports, translation backups, Android backup or Google Drive backup. Claude charges go to your own Anthropic account. Licensed under GPLv3, like upstream.
