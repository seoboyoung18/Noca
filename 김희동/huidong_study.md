## 보유 데이터 기반 빅데이터 분산 적용 기법에 대한 학습

1. "실시간+대용량"이 맞는지
절반만 맞습니다. 정확히는 **"실시간"이 아니라 "실시간이든 아니든, 한 대로 못 버틸 만큼 크거나 빠를 때"**예요. 두 가지 트리거가 따로 있어요:

트리거	예시	실시간 여부
속도(Velocity) 문제	사고 이미지가 초당 수백 장씩 쏟아짐	실시간
용량(Volume) 문제	58만 장 + 205만 건을 한꺼번에 처리해야 함	배치(실시간 아님)
즉 귀하가 앞서 짚었던 "①배치로 견적 통계 테이블 만들기"도 실시간이 아니지만 엄연히 빅데이터 분산 업무예요 (205만 건을 한 대가 순차로 훑으면 오래 걸리니까). 반대로 "②실시간 스트리밍"은 데이터량은 작아도(3장/분) 속도 요건 때문에 분산 구조를 미리 대비하는 경우고요.

그래서 정확한 문장은: **"빅데이터 분산은 '용량이 크거나' 또는 '속도가 빨라서' 한 대의 컴퓨터/DB로는 정해진 시간 안에 못 끝내는 작업에 적합하다"**입니다. 실시간은 그 중 하나의 유발 조건일 뿐이에요.

2. MapReduce 구체적 예시
귀하 데이터로 실제 예시를 만들어볼게요. 목표: "부품(part)별 평균 수리비" 구하기.

원본 데이터 (8건, 실제론 205만 건)
id	part	수리비
1	Front bumper	30만
2	Rear bumper	20만
3	Front bumper	50만
4	Trunk lid	40만
5	Front bumper	40만
6	Rear bumper	60만
7	Trunk lid	20만
8	Rear bumper	40만
한 대로 처리하면: 8건 순서대로 훑으면서 딕셔너리에 누적 — 8건이면 눈 깜짝할 새지만, 205만 건이면 이 순차 스캔 자체가 병목이 됩니다. 그래서 나눕니다.

Step 1. Partition (데이터를 워커별로 쪼갠다)
워커 3대가 있다고 하면:


Worker A: id 1,2,3   (Front bumper 30만 / Rear bumper 20만 / Front bumper 50만)
Worker B: id 4,5,6   (Trunk lid 40만 / Front bumper 40만 / Rear bumper 60만)
Worker C: id 7,8     (Trunk lid 20만 / Rear bumper 40만)
→ 이 시점부터 세 워커는 서로의 데이터를 전혀 몰라도 됩니다. 각자 자기 몫만 갖고 있으면 됨.

Step 2. Map (각 워커가 "똑같은 규칙"으로 자기 몫을 (key, value) 쌍으로 변환)
세 워커 모두 동일한 코드를 동시에 실행합니다: (row) → (part, 수리비)


Worker A 결과: (Front bumper, 30만), (Rear bumper, 20만), (Front bumper, 50만)
Worker B 결과: (Trunk lid, 40만), (Front bumper, 40만), (Rear bumper, 60만)
Worker C 결과: (Trunk lid, 20만), (Rear bumper, 40만)
여기가 병렬성의 핵심이에요 — A, B, C는 서로 기다릴 필요 없이 동시에 이 변환을 끝냅니다. 워커가 10대, 100대여도 이 단계는 똑같은 원리로 계속 빨라집니다(선형에 가깝게 확장).

Step 3. Shuffle (같은 key끼리 한곳으로 모은다)
각 워커의 결과를 key(part) 기준으로 재배치합니다. 이건 워커 간 통신이 필요한 유일한 단계예요:


Front bumper → [30만, 50만, 40만]   (A에서 2개, B에서 1개)
Rear bumper  → [20만, 60만, 40만]   (A에서 1개, B에서 1개, C에서 1개)
Trunk lid    → [40만, 20만]          (B에서 1개, C에서 1개)
Step 4. Reduce (같은 key끼리 모인 값들을 집계)
이번엔 각 key 그룹을 (역시 병렬로, 다른 워커들이) 평균 계산:


Front bumper → (30+50+40)/3 = 40만
Rear bumper  → (20+60+40)/3 = 40만
Trunk lid    → (40+20)/2    = 30만
최종 결과
part	평균 수리비
Front bumper	40만
Rear bumper	40만
Trunk lid	30만
이게 바로 앞서 말씀드린 **"견적 통계 테이블"**의 실제 계산 방식이고, Spark에서는 이 4단계가 각각 .map() → (내부적으로) shuffle → .groupByKey() → .reduce() 같은 API 호출로 대응됩니다. 205만 건이어도 워커 수만 늘리면 Step 2(Map)는 거의 그대로 빨라집니다 — 왜냐하면 각 워커가 서로 독립적으로, 미리 정해진 동일한 함수를 자기 몫에만 돌리기 때문이에요 (학습이 아니라는 게 여기서도 확인되죠 — "똑같은 규칙"을 그대로 반복 적용할 뿐).

스트리밍이면 뭐가 다른가
배치는 "205만 건이 이미 다 있는 상태"에서 한 번 이 4단계를 돌리는 거고, 스트리밍(Kafka+Spark Streaming 같은 조합)은 **"데이터가 계속 조금씩 도착할 때마다, 도착한 만큼을 작은 배치(micro-batch)로 묶어서 위 4단계를 반복"**하는 것뿐이에요. 원리는 완전히 동일하고, 다른 건 "한 번에 다 있냐 vs 계속 흘러들어오냐"뿐입니다.

## 이상탐지 모델 학습
① 순수 CNN 계열 — 여전히 건재함
ResNet, EfficientNetV2, MobileNet 같은 고전 CNN들이 지금도 많이 쓰입니다. < cite index="25-1">데이터가 적거나 연산 예산이 낮은 환경에서는 EfficientNet이나 ConvNeXt가 비슷한 크기의 ViT보다 오히려 성능이 좋은 경우가 많습니다.</cite> < cite index="24-1">MobileNetV3는 지금도 모바일/엣지의 기본 선택지이고, 저전력 기기에서 양자화도 잘 됩니다.</cite>

② 순수/계층형 Transformer 계열 — 대규모 데이터·연산이 받쳐줄 때 최강
ViT, Swin, DeiT 등이 여기 속합니다. < cite index="25-1">특히 Swin Transformer는 detection 분야에서 기존 ResNet 계열 백본을 상당 부분 대체하며 지배적인 백본이 됐고,</cite> COCO 최상위권 시스템들이 Swin 백본을 씁니다.

③ 하이브리드 CNN-Transformer — 사실 실무에서 제일 흔함
< cite index="22-1">CNN의 지역적 특징 추출과 Transformer의 전역 문맥을 결합한 하이브리드가 점점 대세가 되고 있습니다.</cite> ConvNeXt가 대표적인데, < cite index="28-1">순수 컨볼루션 모델이면서도 ViT에서 영감을 받은 설계(큰 커널, LayerNorm, GELU 등)를 CNN 구조에 이식한 것</cite>이라 "CNN이지만 ViT의 아이디어를 빌려온" 형태입니다. 모바일 쪽엔 MobileViT, EdgeNeXt 같은 경량 하이브리드도 있고요.

그럼 YOLO는?
지금 쓰려는 YOLO 계열은 v3~v8까지는 < cite index="20-1">Darknet/CSPDarknet 기반의 순수 CNN 구조였고,</cite> 최근 버전(YOLO12, YOLO26 등)에서 일부 경량 attention 요소를 넣기 시작했을 뿐 핵심은 여전히 CNN입니다. 이건 단점이 아니라 의도된 선택이에요 — 실시간 객체탐지는 지연시간이 생명인데, 순수 ViT는 연산량과 지연시간이 CNN보다 불리한 경우가 많아서 실시간성이 중요한 태스크에는 지금도 CNN·하이브리드가 더 널리 쓰입니다.