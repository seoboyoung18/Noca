# A307 백엔드 — Spring Boot 4.1.1 · Java 21
#
# ── 빌드 (두 단계다. jar 를 먼저 만든다) ──────────────────────
#   cd backend && ./gradlew bootJar -x test
#   cd .. && docker build -t a307-backend:latest -f backend/Dockerfile .
#
#   ※ 컨텍스트는 저장소 루트다. backend/ 에서 빌드하지 않는다.
#   ※ 이 이미지는 네트워크 없이 빌드된다. 아래 "왜 안에서 Gradle 을 돌리지 않나" 참고.
#
# ── 실행 ─────────────────────────────────────────────────────
#   docker run -d --name a307-backend -p 8080:8080 \
#     --env-file /etc/a307/backend.env \
#     --restart unless-stopped \
#     a307-backend:latest
#
#   환경변수는 파일로 넣는다. 명령줄에 키를 적으면 ps 에 보인다.
#
# ⚠️ 실행 전에 마이그레이션을 먼저 적용한다.
#    spring.jpa.hibernate.ddl-auto=validate 라서 스키마가 어긋나면 그 API 만이 아니라
#    앱 전체가 기동하지 않는다. Docs/Architecture/배포 준비 체크리스트.md §2 참고.
#
# ── 왜 안에서 Gradle 을 돌리지 않나 ───────────────────────────
#   처음엔 멀티스테이지로 컨테이너 안에서 빌드했는데, 이 개발 환경에서는
#   컨테이너 egress 가 막혀 있어 Gradle 배포본·의존성을 받지 못했다
#   (호스트는 되고 이미지 pull 도 되는데 컨테이너에서만 안 나간다).
#
#   미리 만든 jar 를 쓰면 그 문제가 사라지고, 부수 효과가 더 크다.
#     · 이미지 빌드가 수 초로 끝난다 (의존성 93MB 를 다시 받지 않는다)
#     · EC2 에서 빌드해도 Gradle 캐시가 필요 없다
#   대신 `./gradlew bootJar` 를 먼저 돌려야 하므로 배포 스크립트가 둘을 묶어야 한다.
#
# ── 왜 레이어를 나누나 ────────────────────────────────────────
#   jar 가 97MB 인데 실측하면 dependencies 93MB · application 8.9MB 다.
#   (application 이 큰 이유는 PDF 한글 폰트 NotoSansKR 5.9MB)
#   한 덩이로 COPY 하면 코드 한 줄만 고쳐도 97MB 를 다시 민다.
#   레이어드 jar 로 쪼개면 실제 전송이 8.9MB 로 줄어든다.

# ─────────────────────────────────────────────────────────────
# 1단계 — 레이어 분해 (네트워크 불필요)
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS extractor
WORKDIR /work

# 디렉터리째 받아서 고른다. 지금은 jar 가 하나뿐이지만 jar 태스크를 켜면
# -plain.jar 가 같이 나오고, COPY 로 여러 개를 한 파일에 받으면 빌드가 깨진다.
COPY backend/build/libs/ ./libs/

# Spring Boot 3.3+ 는 layertools 가 없어졌다. jarmode=tools 를 쓴다.
# 추출 결과는 dependencies · spring-boot-loader · snapshot-dependencies · application 4개다.
RUN set -eux; \
    JAR="$(ls libs/*.jar | grep -v -- '-plain\.jar$' | head -n 1)"; \
    echo "boot jar = $JAR"; \
    java -Djarmode=tools -jar "$JAR" extract --layers --launcher --destination extracted

# ─────────────────────────────────────────────────────────────
# 2단계 — 실행
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV TZ=Asia/Seoul
ENV LANG=C.UTF-8

# apt-get 을 쓰지 않는다. 이 이미지(Ubuntu 26.04 기반)에 필요한 것이 이미 있다 — 실측 확인:
#   curl                    HEALTHCHECK 용
#   libfreetype · libfontconfig · fc-list
#   java.desktop 모듈
# 이 셋이 없으면 PDF 생성(openhtmltopdf-pdfbox)과 이미지 전처리(ImageIO·java.awt)가
# 런타임에 죽는다. 증상이 기동 실패가 아니라 "그 요청만 500" 이라 배포 후에야 드러난다.
# 베이스 이미지를 바꿀 때는 위 셋이 있는지 다시 확인할 것.

# root 로 돌리지 않는다.
RUN groupadd --system --gid 1001 a307 \
 && useradd  --system --uid 1001 --gid a307 --no-create-home a307

# 변하는 빈도가 낮은 것부터 COPY 한다. 위 레이어가 캐시된다.
COPY --from=extractor --chown=a307:a307 /work/extracted/dependencies/          ./
COPY --from=extractor --chown=a307:a307 /work/extracted/spring-boot-loader/    ./
COPY --from=extractor --chown=a307:a307 /work/extracted/snapshot-dependencies/ ./
COPY --from=extractor --chown=a307:a307 /work/extracted/application/           ./

# 견적서 파일의 로컬 저장 경로(DOCUMENT_STORAGE_LOCAL_ROOT 기본값).
# DOCUMENT_STORAGE_PROVIDER=s3 면 쓰이지 않지만 로컬 폴백일 때 쓰기 권한이 필요하다.
RUN mkdir -p /app/var/estimate-documents && chown -R a307:a307 /app/var

USER a307
EXPOSE 8080

# -Xmx 를 고정하지 않는 이유 — EC2 스펙이 바뀌어도 이미지를 다시 만들지 않기 위해서다.
# 75% 는 힙 밖(메타스페이스·스레드 스택·다이렉트 버퍼)을 남긴 값이다.
# ExitOnOutOfMemoryError 를 켜는 이유 — OOM 뒤에 반쯤 죽은 채로 살아 있으면
# 헬스체크는 통과하는데 요청만 실패한다. 차라리 죽고 재시작되는 편이 낫다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"

# "기동했다" 까지만 보증한다. 어댑터가 없어도 UP 이 뜨므로
# 기능 확인은 배포 준비 체크리스트 §5 로 따로 한다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
  CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
