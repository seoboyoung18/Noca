#!/usr/bin/env bash
# S15P21A307-159 · 분석 응답 시간 측정
#
# 결과와 해석은 "분석 응답 시간 측정 (S15P21A307-159).md" 에 있다.
# 운영 서버(j15a307)에서 돌린다 — AI 서버가 사설망에만 열려 있다.
#
#   analysis-latency.sh run <장수>   측정용 사고를 만들고 분석을 걸어 구간을 잰다
#   analysis-latency.sh report       지금까지 잰 측정용 사고의 구간을 표로 낸다
#   analysis-latency.sh clean        측정용 사고·회원을 전부 지운다
#
# 사진은 새로 올리지 않는다. 이미 S3 에 있는 RESIZED 자산을 가리키는 행만 만들고,
# AI 는 presigned URL 로 그 사진을 진짜로 내려받아 추론한다.
set -eu

set -a
. /etc/a307/backend.env
set +a
export PGPASSWORD="$DB_PASSWORD"
PSQL=(psql -h 172.26.4.46 -U "$DB_USERNAME" -d a307 -v ON_ERROR_STOP=1)
Q=("${PSQL[@]}" -t -A -q)   # -q: INSERT 0 1 같은 명령 태그가 값에 섞이지 않게 한다

TAG='perf-159-measure'

ensure_fixture() {
    "${Q[@]}" >/dev/null <<SQL
INSERT INTO member (provider, provider_user_id, nickname, role, status)
SELECT 'KAKAO', '$TAG', '성능측정', 'USER', 'ACTIVE'
 WHERE NOT EXISTS (SELECT 1 FROM member WHERE provider_user_id = '$TAG');

INSERT INTO vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)
SELECT '현대', '성능측정차', 'SEDAN', 'Mid-size', false
 WHERE NOT EXISTS (SELECT 1 FROM vehicle_model WHERE model_name = '성능측정차');

INSERT INTO vehicle (member_id, model_id, model_year)
SELECT m.member_id, vm.model_id, 2021
  FROM member m, vehicle_model vm
 WHERE m.provider_user_id = '$TAG' AND vm.model_name = '성능측정차'
   AND NOT EXISTS (SELECT 1 FROM vehicle v WHERE v.member_id = m.member_id);
SQL
}

run() {
    local n="$1"
    ensure_fixture
    local accident
    accident=$("${Q[@]}" <<SQL
WITH v AS (
    SELECT v.vehicle_id, v.model_id FROM vehicle v
      JOIN member m ON m.member_id = v.member_id
     WHERE m.provider_user_id = '$TAG'
)
INSERT INTO accident (vehicle_id, vehicle_input_type, snapshot_model_id,
                      snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                      snapshot_car_class, snapshot_model_year)
SELECT v.vehicle_id, 'REGISTERED', v.model_id, '현대', '성능측정차', 'SEDAN', 'Mid-size', 2021
  FROM v
RETURNING accident_id;
SQL
)
    accident=$(echo "$accident" | head -1 | tr -d "[:space:]")

    "${Q[@]}" >/dev/null <<SQL
DO \$\$
DECLARE
    src        RECORD;
    v_image_id BIGINT;
    total      INT;
    i          INT;
BEGIN
    SELECT count(*) INTO total FROM accident_image_asset WHERE variant = 'RESIZED';
    FOR i IN 1..$n LOOP
        SELECT a.s3_key, a.width, a.height, a.file_size INTO src
          FROM accident_image_asset a
         WHERE a.variant = 'RESIZED'
         ORDER BY a.image_id
        OFFSET ((i - 1) % total) LIMIT 1;

        INSERT INTO accident_image (accident_id, original_filename, angle_code, quality_status)
        VALUES ($accident, 'perf-' || i || '.jpg',
                (ARRAY['FRONT','FRONT_LEFT','FRONT_RIGHT','SIDE_LEFT',
                       'SIDE_RIGHT','REAR','REAR_LEFT','REAR_RIGHT'])[((i - 1) % 8) + 1],
                'PASS')
        RETURNING image_id INTO v_image_id;

        INSERT INTO accident_image_asset (image_id, variant, s3_key, width, height, file_size)
        VALUES (v_image_id, 'RESIZED', src.s3_key, src.width, src.height, src.file_size);
    END LOOP;
END \$\$;
SQL

    # 워커가 집어 갈 큐에 넣는다. 여기서부터는 사용자가 요청한 것과 같은 경로다.
    local job
    job=$("${Q[@]}" -c "INSERT INTO analysis_job (accident_id, status) VALUES ($accident, 'QUEUED') RETURNING job_id;" | head -1 | tr -d "[:space:]")
    echo "사진 ${n}장 · accident=${accident} · job=${job} 큐 투입"

    local waited=0
    while [ "$waited" -lt 120 ]; do
        sleep 3
        waited=$((waited + 3))
        local status
        status=$("${Q[@]}" -c "SELECT status FROM analysis_job WHERE job_id = $job;")
        if [ "$status" = "COMPLETED" ] || [ "$status" = "FAILED" ]; then
            "${PSQL[@]}" -c "
SELECT $n AS 사진수, job_id, status,
       round(EXTRACT(epoch FROM (started_at  - created_at))::numeric, 2) AS \"접수→전송\",
       round(EXTRACT(epoch FROM (finished_at - started_at))::numeric, 2) AS \"전송→수신\",
       round(EXTRACT(epoch FROM (finished_at - created_at))::numeric, 2) AS \"전체\"
  FROM analysis_job WHERE job_id = $job;"
            return 0
        fi
    done
    echo "120초 안에 끝나지 않았다: job=$job"
    return 1
}

report() {
    "${PSQL[@]}" <<SQL
\pset title '측정 결과 (측정용 사고만)'
SELECT (SELECT count(*) FROM accident_image i WHERE i.accident_id = j.accident_id) AS 사진수,
       j.job_id, j.status,
       round(EXTRACT(epoch FROM (j.started_at  - j.created_at))::numeric, 2) AS "접수→전송",
       round(EXTRACT(epoch FROM (j.finished_at - j.started_at))::numeric, 2) AS "전송→수신",
       round(EXTRACT(epoch FROM (j.finished_at - j.created_at))::numeric, 2) AS "전체"
  FROM analysis_job j
  JOIN accident a ON a.accident_id = j.accident_id
  JOIN vehicle v ON v.vehicle_id = a.vehicle_id
  JOIN member m ON m.member_id = v.member_id
 WHERE m.provider_user_id = '$TAG' AND j.finished_at IS NOT NULL
 ORDER BY 사진수, j.job_id;

\pset title '사진 수별 중앙값'
SELECT 사진수, count(*) AS 횟수,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY 전송_수신)::numeric, 2) AS "전송→수신 중앙값",
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY 전체)::numeric, 2) AS "전체 중앙값",
       round(max(전체)::numeric, 2) AS "전체 최대"
  FROM (
    SELECT (SELECT count(*) FROM accident_image i WHERE i.accident_id = j.accident_id) AS 사진수,
           EXTRACT(epoch FROM (j.finished_at - j.started_at)) AS 전송_수신,
           EXTRACT(epoch FROM (j.finished_at - j.created_at)) AS 전체
      FROM analysis_job j
      JOIN accident a ON a.accident_id = j.accident_id
      JOIN vehicle v ON v.vehicle_id = a.vehicle_id
      JOIN member m ON m.member_id = v.member_id
     WHERE m.provider_user_id = '$TAG' AND j.finished_at IS NOT NULL) t
 GROUP BY 사진수 ORDER BY 사진수;
SQL
}

clean() {
    "${PSQL[@]}" <<SQL
DELETE FROM accident a
 USING vehicle v, member m
 WHERE a.vehicle_id = v.vehicle_id AND v.member_id = m.member_id
   AND m.provider_user_id = '$TAG';
DELETE FROM vehicle v USING member m
 WHERE v.member_id = m.member_id AND m.provider_user_id = '$TAG';
DELETE FROM member WHERE provider_user_id = '$TAG';
DELETE FROM vehicle_model WHERE model_name = '성능측정차';
SQL
}

case "${1:-}" in
    run)    run "${2:-8}" ;;
    report) report ;;
    clean)  clean ;;
    *)      echo "사용법: analysis-latency.sh {run <장수>|report|clean}"; exit 1 ;;
esac
