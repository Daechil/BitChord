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
| 바뀐 내용 | 빌드마다 바뀐 내용은 각 릴리스 설명과 [KO_CHANGELOG.md](KO_CHANGELOG.md)에 적혀 있어요. |
| 버전 표기 | `1.9.1-ko.N`이에요. 앞부분은 바탕이 된 원본 버전이고, `N`은 이 포크의 빌드 번호예요. 설정의 버전, 파일 이름, 릴리스 태그가 모두 같아요. |

> [!NOTE]
> 이 포크만의 서명 키로 서명돼요. 그래서 공식 BitChord 위에 덮어서 설치하거나, 그 반대로 업데이트할 수는 없어요.

---

## 추가된 기능

새로 추가한 설정은 모두 **설정 > BitChord KO 기능** 탭에 모여 있어요.

### Claude AI 가사 번역

내 Claude API 키를 넣으면, 가사 번역을 Google 번역 대신 **Claude**가 해요. 노래 전체를 읽고 말투와 흐름을 맞춰서 번역하기 때문에 자연스러워요.

- **모델 선택**: Haiku 5.5(기본, 빠르고 저렴)와 Sonnet 5.5(더 자연스러움, 약 20배 비쌈) 중에서 고를 수 있어요.
- **번역 말투**: 노래에 맞게 Claude가 선택 / 친근하게(반말) / 정중하게(존댓말).
  - 한국어뿐 아니라 친근한 말과 정중한 말이 나뉘는 번역 언어라면 어디에나 적용돼요. 예: 일본어 タメ口/です・ます, 프랑스어 tu/vous, 독일어 du/Sie.
- **번역 방식**: 의역(뜻을 자연스럽게) / 직역(원문에 가깝게).
- **번역한 엔진 표시**
  - Claude가 번역하면 번역 버튼에 작은 ✦ 표시가 붙고, 가사 아래 상태 줄에 `한국어 번역 · Claude`라고 나와요.
  - Claude가 실패해서 Google 번역으로 대신했으면 `Google 번역 (Claude: 시간 초과)`처럼 이유가 함께 나와요.
- **다시 번역**: 번역 버튼을 **길게 누르면** 저장된 번역을 무시하고 새로 받아요.
- **Haiku가 거절하면 Sonnet으로 한 번 더**
  - Haiku는 가끔 가사를 저작권 문제로 보고 번역을 거절해요. 그러면 같은 요청을 Sonnet으로 한 번 더 보내요.
  - 이때 상태 줄은 `Claude Sonnet (Haiku 거절)`로 표시돼요.
  - Sonnet도 거절하면 Google 번역으로 보여줘요.
  - 설정의 **Haiku 거절 시 Sonnet 재시도**로 끌 수 있어요. 끄면 Haiku가 거절한 곡은 바로 Google 번역으로 넘어가요.
- **줄 수 맞춤**: 줄마다 따로 칸을 받는 방식으로 요청하기 때문에, 번역이 원문과 한 줄씩 정확히 맞아요.
- **번역 저장**: 번역한 곡은 기기에 저장해 두고, 다시 들을 때는 요청을 보내지 않아요.
- 키가 없거나 Claude 번역을 끄면, 원래 앱처럼 Google 번역을 써요.

### 발음 표기 (설정 › Claude AI › 발음·번역 규칙)

가사 화면의 **발음(로마자) 버튼**을 누르면 외국어 가사의 발음을 보여줘요. 세 가지 중에서 골라요.

| 방식 | 내용 | 요금 |
|---|---|---|
| **무료 한글 발음** (기본) | Google 로마자를 휴대폰에서 한글로 바꿔요: `aishiteru → 아이시테루`, `nǐ hǎo → 니 하오`. 일본어·중국어. 원문에 영어로 적힌 단어(baby 등)는 그대로 둬요. | 없음 |
| **Claude 발음** | 한자 읽는 법까지 가장 정확해요. 번역 언어의 글자로 적어요(한국어는 한글, 일본어는 가타카나 등). | 번역 한 번 정도 |
| **로마자 그대로** | Google 로마자를 그대로 보여줘요. | 없음 |

- **발음 버튼을 길게 누르면** 어떤 방식을 골랐든 그 곡만 Claude 발음을 받아요. 무료 발음이 이상한 곡에 쓰면 돼요.
- 무료 한글 발음을 골라도, 전에 Claude로 받아 둔 발음(미리 번역 포함)이 있으면 그걸 먼저 보여줘요. 요금은 들지 않아요.
- Claude 발음은 한 번 받으면 저장돼서 같은 곡에서는 다시 요금이 들지 않아요. 상태 줄에 `발음 · Claude`라고 나와요.

### 번역 지시문 (설정 › Claude AI › 발음·번역 규칙)

"아티스트 이름은 번역하지 말 것", "반말로"처럼 **Claude에게 주는 규칙을 직접 적을 수 있어요**(최대 500자). 예시 버튼으로 바로 넣을 수도 있어요.

- Claude 번역·발음·미리 번역에 함께 보내요. 노래 해석에도 보낼지는 따로 골라요.
- 이미 받은 번역은 그대로 남아요. 새 지시문으로 받으려면 **번역 버튼을 길게 누르세요.**

### 이 노래 해석

가사 화면의 ✦ 버튼을 누르면 Claude가 노래를 해석해 줘요. 한 줄 요약, 이야기와 감정, 주요 비유와 표현, 눈여겨볼 가사 몇 줄을 보여줘요. 한 번 받은 해석은 저장돼서 다음에는 바로 열려요.

### 원제 찾기 (가사 검색 보완)

한국어로 적힌 외국 곡은 가사를 못 찾는 경우가 많아요. 예를 들어 '발묘 / 나나호시 관현악단'은 원래 '抜錨 / ナナホシ管弦楽団'이에요.
가사를 못 찾으면 YouTube Music과 Claude로 **원래 제목과 아티스트**를 알아낸 뒤, 그 이름으로 가사를 다시 찾아요. 한 번 알아낸 이름은 기억해 둬요.

### Claude 사용량, 월 한도, 진단 기록

- **이번 달 Claude 사용량**: 모델별 입력·출력 토큰 수와 예상 요금(달러)을 보여줘요. 매달 새로 세고, 직접 초기화할 수도 있어요.
- **월 사용 한도**
  - 없음 / $0.50 / $1 / $2 / $5 / $10 / $20 중에서 골라요. 기본은 없음이에요.
  - 이번 달 예상 요금이 한도에 닿으면 Claude에 더 보내지 않고, 다음 달까지 Google 번역을 써요. 상태 줄에는 `Google 번역 (Claude: 월 한도 도달)`이라고 나와요.
  - 예상 요금은 앱이 토큰 수로 계산한 값이에요. 실제 청구액과 조금 다를 수 있어요.
- **금액·한도 설정** (설정 › Claude AI › 금액·한도 설정)
  - **금액 표시**: 달러 / 원화 / 달러와 원화(기본) 중에서 골라요. 원화는 계산값이라 '약'을 붙여 보여줘요.
  - **환율**: 자동(하루 1번, 기본) 또는 직접 입력. 자동은 유럽중앙은행 기준환율(키 없음)이나 한국수출입은행 매매기준율(무료 인증키, 이 기기에만 저장)에서 받아요. 기준일과 출처가 같이 보여요.
  - **카드 해외결제 수수료**: 0~3%를 골라 원화 금액에 더할 수 있어요.
  - **경고 알림**: 월 한도의 50% / 80%(기본) / 90%를 쓰면 알림이 와요. 한도에 닿을 때도 한 번 알려요. 결과를 기다리는 미리 번역 비용도 포함해요.
  - **하루 한도**: 없음(기본) / $0.10 / $0.20 / $0.50 / $1 / $2. 그날 실시간 번역에 쓴 금액이 닿으면 자정까지 Google 번역을 써요. 미리 번역(Batch)은 하루 한도에 넣지 않아요.
- **Claude 진단 기록**
  - 최근 50건의 요청이 어떻게 됐는지 보여줘요: 성공·실패, 실패 이유, 걸린 시간, 줄 수, 토큰 수.
  - **API 키와 가사는 기록하지 않아요.**
  - '기록 복사'로 복사해서 문제를 알릴 때 붙여 넣으면 돼요.

### 다운로드 곡 번역 보관 (기본 켜짐)

- 곡을 다운로드하면 백그라운드에서 가사를 미리 번역해 둬요. Claude가 켜져 있으면 Claude로, 꺼져 있으면 Google로 번역해요.
- 다운로드한 곡의 번역은 캐시가 아닌 **영구 보관함**에 저장돼요(최대 100MB). 캐시를 지워도 남아 있고, **오프라인에서도 번역이 바로 떠요.**
- 앨범을 한꺼번에 받아도 요청이 몰리지 않게 한 곡씩 차례로 번역해요.

### 재생목록·앨범 가사 미리 번역 (반값)

재생목록이나 앨범의 가사를 한 묶음으로 Claude에 보내 미리 번역해 둬요. Anthropic의 Batch API를 써서 **요금이 일반 번역의 절반**이에요.

- 재생목록·앨범의 ⋯ 메뉴에서 **가사 미리 번역**을 눌러요.
- 창에서 모델(Haiku / Sonnet)과 **발음 표기도 함께 받기**를 골라요. 모델마다 예상 비용이 나와요.
- 결과는 바로 오지 않아요. 보통 1시간 안, 길면 24시간이 걸리고, 끝나면 알림이 와요.
- 진행 상황은 알림바에서 볼 수 있어요. 가사 찾는 중에는 진행 막대와 취소 버튼, 결과를 기다리는 중에는 '묶음 n개 중 m개 완료'와 지금 확인 버튼이 나와요.
- 곡이 많으면 20곡씩(설정 › Claude AI › 미리 번역 묶음 크기에서 10/20/30곡) 묶어서 나눠 보내요. 중간에 앱이 꺼져도 다시 열면 이어서 진행하고, 이미 보낸 곡은 다시 보내지 않아요.
- 이미 번역된 곡, 가사가 없는 곡, 이미 번역 언어로 된 곡은 보내지 않아요.
- 플레이어에서 들어 본 곡은 그때 본 가사를 그대로 보내요(최근 500곡 기억). 가사를 시간 초과나 연결 오류로 못 가져온 곡은 한 번 더 찾고, 기록에 따로 표시돼요.
- 받은 번역은 지워지지 않는 보관함에 저장돼요. 그 곡을 틀면 요청 없이 바로 나와요.
- **설정 › Claude AI › 미리 번역 기록**에서 작업별 상태, 곡별 결과, 실제 비용을 볼 수 있어요. 거절된 곡만 Sonnet으로 다시 보낼 수도 있어요.
- 예상 비용이 남은 월 한도를 넘으면 보내지 않아요.
- 번역 말투·방식·언어를 나중에 바꾸면 미리 받은 번역은 쓰이지 않아요.
- **새 곡 자동 미리 번역**: 창에서 '새 곡이 추가되면 자동으로 미리 번역'을 체크하면, 그 재생목록을 정해진 주기(기본 24시간, 설정 › Claude AI › 새 곡 확인 주기)마다 확인해 새 곡만 보내요.
- **다운로드 곡 번역을 Batch로** (설정 › BitChord KO 기능 › 다운로드, 기본 꺼짐): 다운로드한 곡의 번역도 모아서 반값으로 보내요. 결과는 최대 24시간 뒤에 와요.

### 번역 상태 확인

곡이 번역돼 있는지, 저장된 번역을 쓰는지 새로 번역하는지 볼 수 있어요.

- **가사 화면 상태 줄**: "Claude · 보관함에서", "캐시에서", "방금 번역"처럼 나와요.
- **곡 메뉴 › 번역 상태**: Claude 번역됨 / 다른 설정으로 번역됨 / Google 번역만 / 번역 없음과 보관함·캐시 여부. 누르면 모델, 언어, 말투, 받은 경로, 날짜까지 보여줘요.
- **재생목록·앨범 메뉴 › 번역 상태 보기**: 곡별 상태와 요약. **번역 안 된 곡만 미리 번역**으로 빠진 곡만 반값에 보낼 수 있어요.
- **설정 › Claude AI › 번역 저장소**: 번역이 있는 곡 목록과 검색, 보관함·캐시 용량과 종류별 용량. 캐시의 Google 번역 비우기, 캐시 전체 비우기, 보관함 비우기, 노래 해석 모두 지우기(보관함·해석은 경고 후 확인), 곡별 번역 지우기, 보관함 한도(100/200/500MB) 설정.
- **Claude 번역 항상 보관** (기본 켜짐): 돈 내고 받은 Claude 번역은 캐시가 아니라 지워지지 않는 보관함에 저장해요.
- 이 기능 이전에 받은 번역은 그 곡을 한 번 더 재생하면 목록에 나타나요.

### 번역 백업·복원

**설정 › BitChord KO 기능 › 다운로드**의 **번역 백업**을 누르면 이 기기에 있는 Claude 번역, 발음 표기, 노래 해석을 zip 파일 하나로 저장해요. **번역 복원**으로 다시 불러와요.

- 휴대폰을 바꾸거나 앱을 다시 설치해도 돈 내고 받은 번역을 그대로 쓸 수 있어요.
- API 키와 설정은 파일에 들어가지 않아요.
- 복원할 때 이미 있는 번역은 덮어쓰지 않고, 복원한 번역은 지워지지 않는 보관함에 들어가요.
- 무료인 Google 번역은 백업하지 않아요.

### 플로팅 가사 (기본 꺼짐)

- 곡이 재생되는 동안 **다른 앱을 쓰고 있으면**, 화면 위에 작은 막대로 지금 부르는 가사와 번역을 띄워요.
- 막대를 **끌어서 옮기고**, 누르면 앱이 열려요. 옮긴 위치는 기억해요.
- 막대를 **꾹 누르면** 사라져요. 다음에 BitChord KO를 열었다가 다른 앱으로 나가면 다시 나타나요. 아예 안 보이게 하려면 설정에서 끄면 돼요.
- **플로팅 가사 고정**을 켜면 막대를 옮길 수 없고, 터치가 아래 앱으로 그대로 전달돼요. 이때는 안드로이드 규칙에 맞춰 막대가 조금 투명해져요.
- BitChord KO 화면을 보고 있을 때나 일시정지 중에는 뜨지 않아요.
- 번역은 플레이어에서 이미 번역해 둔 곡만 보여줘요. 플로팅 가사가 따로 번역을 요청하지는 않아요.
- 가사는 플레이어가 보여주는 가사를 그대로 써요(직접 고른 출처 포함). 시간 정보가 없는 가사는 띄우지 않아요.
- **설정 › 플로팅 가사 모양·싱크·위치** 창에서 미리보기를 보면서 고를 수 있어요.
  - 글자 크기, 배경 불투명도, 표시 내용(원문과 번역 / 원문만 / 번역만)
  - 다음 줄 미리 보기, 글자 테두리(없음 / 그림자 / 테두리), 번역 글자 색(6가지)
  - 싱크: 플레이어의 가사 오프셋을 따르고, 플로팅 가사만 ±1초까지 더 조절해요.
  - 위치만 고정(누르기·꾹 누르기는 그대로), 위치 초기화
- 잠금화면 가사와 같은 **'다른 앱 위에 표시' 권한**을 써요.

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
키를 넣으면 모델·번역 말투·번역 방식·발음 표기·Sonnet 재시도·사용량·월 한도·진단 기록 메뉴가 나타나요.

### 3. 요금

요금은 **내 Anthropic 계정에 사용한 만큼** 청구돼요. 실제 사용 기록 기준으로 대략 이 정도예요.

| 모델 | 100만 토큰당 (입력 / 출력) | 노래 한 곡 번역 |
|---|---|---|
| Haiku 5.5 (기본) | $0.10 / $0.50 | 약 $0.001~0.002 (1~3원) |
| Sonnet 5.5 | $2 / $10 | 약 $0.02 (25~30원) |

- 같은 곡은 저장된 번역을 쓰기 때문에 다시 요금이 들지 않아요.
- 노래 해석과 원제 찾기는 요청할 때마다 따로 요금이 들어요. 원제 찾기는 아주 적어요.
- 발음 표기는 발음 버튼을 누른 곡만 요금이 들고, 번역 한 번과 비슷한 정도예요.
- Haiku가 거절한 곡을 Sonnet으로 다시 보내면 그 곡은 Sonnet 요금이 들어요. 원하지 않으면 재시도를 끄거나 **월 사용 한도**를 정해 두세요.
- 정확한 요금은 [Anthropic 가격표](https://www.anthropic.com/pricing)와 콘솔의 사용 내역을 확인하세요.

### 4. 보내는 정보

- **Anthropic에 보내는 것**: 번역하거나 발음을 적을 가사, 노래 제목과 아티스트(노래 해석·원제 찾기에 사용)뿐이에요.
- **키 보관**: 키는 **이 기기에만, 암호화해서** 저장돼요. 설정 내보내기 파일에도, Android 백업·기기 이전에도 들어가지 않아요.
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
  - Choice of Haiku 5.5 or Sonnet 5.5, casual/polite speech level (in any target language that has one) and natural/literal style.
  - The status line names the engine and the reason for any fallback.
  - Long-press the translate button to re-translate.
  - If Haiku declines a song, it is asked again once with Sonnet (can be switched off); Google Translate is used if that fails too.
- **Claude pronunciation**: the romanize button shows how foreign lyrics sound, written in your translation language's script (Hangul for Korean, katakana for Japanese, Cyrillic, standard romanization for Latin-script languages, and so on).
- **Song meaning**: Claude explains the song.
- **Original-title lookup** for songs written in Hangul, so lyrics can be found under their original names.
- **Usage counter, monthly spending limit and diagnostics log**. Past the limit, Google Translate is used until next month. The log never contains the key or the lyrics.
- **Floating lyrics**: a small draggable bar with the current line over other apps; tap to open the app, hold to hide it until the app is next opened. Needs "Display over other apps".
- **Kept translations for downloads**: translated at download time and kept for offline use.
- **Lock-screen lyrics**. Needs "Display over other apps".
- **Resume when headphones connect**.

Charges for Claude go to your own Anthropic account. Licensed under GPLv3, like upstream.
