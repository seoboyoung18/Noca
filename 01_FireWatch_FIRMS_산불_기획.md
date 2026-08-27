# FireWatch — NASA FIRMS 위성 열원 데이터 기반 산불 탐지 플랫폼 기획서

> 프로젝트명 후보: **FireWatch / FireFlow / Satellite Fire Monitor**
> 작성일: 2026-08-27

---

## 1. 최종 프로젝트 주제

**NASA FIRMS 위성 열원 데이터 기반 산불 의심지역 근실시간 탐지·분산분석 및 대응 우선순위 지원 플랫폼**

## 2. 프로젝트 목적

NASA 위성이 탐지한 열원(hotspot) 데이터를 자동으로 수집하고, 대규모 데이터를 Hadoop과 Spark로 분석하여 다음 정보를 제공한다.

- 산불·화재가 의심되는 위치를 지도에 표시
- 같은 지역에서 열원이 반복 탐지되는지 분석
- 열원의 세기와 증가·감소 추세 확인
- 산림·도시·농경지 등 주변 환경 확인
- 담당자가 먼저 확인해야 할 지역을 우선순위로 제공
- 과거 데이터 재생을 통해 확산 과정과 시스템 처리 성능 시연

## 3. 주요 사용자

- 산림청·산불방지센터
- 지자체 재난안전 담당자
- 소방 상황실
- 산림·환경 연구기관

## 4. 아키텍처 (데이터 파이프라인)

```
NASA FIRMS 위성 데이터
→ Kafka로 근실시간 수집·재생
→ Apache Hadoop HDFS에 분산 저장
→ Spark로 위치·시간·반복 탐지 분석
→ PostGIS에 공간정보 저장
→ 웹 지도에서 산불 의심지역과 우선순위 제공
```

상세 데이터 흐름:

```
VIIRS·MODIS 위성
→ NASA 지상국·FIRMS 처리 시스템
→ FIRMS Area API
→ 우리 프로젝트 Collector
→ Apache Hadoop HDFS
→ Spark 분석
→ Kafka·PostGIS·지도 화면
```

## 5. 이 시스템이 해결하는 문제

기존에는 담당자가 많은 열원 데이터를 직접 확인해야 한다. 이 시스템은 여러 위성에서 들어오는 열원 데이터를 자동으로 모아서
**“어디에서 열원이 발견됐고, 어느 지역을 먼저 확인해야 하는가?”** 를 알려준다.

## 6. 하지 않는 것 (범위 밖)

- 산불을 100% 확정하지 않음
- 방화·실화·낙뢰 같은 원인을 확정하지 않음
- 소방 출동을 자동으로 결정하지 않음
- 공식 재난 경보를 대체하지 않음

## 7. NASA FIRMS API 활용 방법

FIRMS는 위성 원본 사진이 아니라 **위성이 탐지한 열원(hotspot) 좌표 데이터**를 제공한다.

> NASA FIRMS API를 통해 VIIRS·MODIS 위성 기반 열원 탐지 데이터를 수집하고, Apache Hadoop HDFS와 Spark로 분산 저장·처리한다.

### 사용할 위성 데이터 소스

| SOURCE 코드 | 위성 |
| --- | --- |
| `VIIRS_SNPP_NRT` | Suomi-NPP VIIRS |
| `VIIRS_NOAA20_NRT` | NOAA-20 VIIRS |
| `VIIRS_NOAA21_NRT` | NOAA-21 VIIRS |
| `MODIS_NRT` | Terra/Aqua MODIS |

### 호출 주소 형식

```
https://firms.modaps.eosdis.nasa.gov/api/area/csv/[MAP_KEY]/[위성_SOURCE]/[서,남,동,북]/[조회일수]
```

대한민국 범위 호출 예시:

```
https://firms.modaps.eosdis.nasa.gov/api/area/csv/본인_MAP_KEY/VIIRS_SNPP_NRT/124,33,132,39/1
```

특정 날짜까지 지정 예시:

```
https://firms.modaps.eosdis.nasa.gov/api/area/csv/본인_MAP_KEY/VIIRS_NOAA20_NRT/124,33,132,39/1/2026-08-18
```

- 무료 `MAP_KEY`는 FIRMS Area API 페이지에서 이메일로 발급
- 조회 범위: bbox 또는 `world`
- 한 번에 조회 기간: 1~5일

## 8. 핵심 데이터 출처 (P0 — NASA FIRMS)

| 자료 | URL |
| --- | --- |
| FIRMS API 안내 | https://firms.modaps.eosdis.nasa.gov/api/ |
| FIRMS Area API | https://firms.modaps.eosdis.nasa.gov/api/area/ |
| 실시간 Active Fire 다운로드 | https://firms.modaps.eosdis.nasa.gov/active_fire/ |
| 과거자료 다운로드 | https://firms.modaps.eosdis.nasa.gov/download/ |
| FIRMS HTTPS Archive | https://nrt3.modaps.eosdis.nasa.gov/archive/FIRMS/ |
| FIRMS Web Services | https://firms.modaps.eosdis.nasa.gov/web-services/ |
| FIRMS 지도 | https://firms.modaps.eosdis.nasa.gov/map/ |
| FIRMS FAQ | https://firms.modaps.eosdis.nasa.gov/faq/ |
| 데이터 사용·인용 안내 | https://firms.modaps.eosdis.nasa.gov/content/academy/data-use.html |

## 9. 보조 데이터 출처

### 화재·연기 이미지/영상 데이터셋

| 데이터셋 | URL |
| --- | --- |
| D-Fire | https://github.com/gaia-solutions-on-demand/DFireDataset |
| AI Hub 화재 71330 | https://www.aihub.or.kr/aihubdata/data/view.do?dataSetSn=71330 |
| AI Hub 화재 71472 | https://www.aihub.or.kr/aihubdata/data/view.do?dataSetSn=71472 |
| AI Hub 전체 검색 | https://www.aihub.or.kr/aihubdata/data/list.do |
| FASDD GitHub | https://github.com/openrsgis/FASDD |
| FLAME 1 | https://doi.org/10.21227/j0b6-en70 |
| FLAME 3 | https://doi.org/10.21227/w0mz-aq48 |
| MmodalFire | https://doi.org/10.6084/m9.figshare.28804448 |
| FURG-Fire | https://github.com/steffensbola/furg-fire-dataset |
| Corsican Fire Database | https://cfdb.univ-corse.fr/ |

### 기상·산불 이력·토지피복 데이터

| 데이터셋 | URL |
| --- | --- |
| ERA5-Land Hourly | https://cds.climate.copernicus.eu/datasets/reanalysis-era5-land |
| Copernicus CDS | https://cds.climate.copernicus.eu/ |
| GWIS GlobFire 다운로드 | https://gwis.jrc.ec.europa.eu/apps/country.profile/downloads |
| ESA WorldCover | https://esa-worldcover.org/en/data-access |
| MODIS MCD12 토지피복 | https://modis.gsfc.nasa.gov/data/dataprod/mod12.php |
| NASA Earthdata Search | https://search.earthdata.nasa.gov/search |

### 국내 산림청 공공데이터 (초기 아이디어)

| 데이터 | URL |
| --- | --- |
| AI-Hub 산불 데이터 71330 | https://aihub.or.kr/aihubdata/data/view.do?dataSetSn=71330 |
| 산림청 산불위험예보정보 | https://www.data.go.kr/data/15084817/openapi.do |
| 산림청 산불발생통계 | https://www.data.go.kr/data/3070842/openapi.do |

## 10. 우선 확보 대상

`FIRMS Area API`, `FIRMS Archive`, `D-Fire`, `AI Hub 71330`, `ERA5-Land`, `GlobFire`, `ESA WorldCover`
