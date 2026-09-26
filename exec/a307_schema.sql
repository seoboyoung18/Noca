--
-- PostgreSQL database dump
--

\restrict HhNeb2ii2l8cAHzRxaeqEUU5jAihxgTJfLV0GUZc1LvFeV15S1D9QDXU52IKyaA

-- Dumped from database version 16.15 (Debian 16.15-1.pgdg12+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg12+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: EXTENSION vector; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION vector IS 'vector data type and ivfflat and hnsw access methods';


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: accident; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.accident (
    accident_id bigint NOT NULL,
    vehicle_id bigint NOT NULL,
    vehicle_input_type character varying(10) NOT NULL,
    snapshot_model_id bigint NOT NULL,
    snapshot_manufacturer character varying(50) NOT NULL,
    snapshot_model_name character varying(100) NOT NULL,
    snapshot_vehicle_type character varying(20) NOT NULL,
    snapshot_car_class character varying(20) NOT NULL,
    snapshot_model_year smallint NOT NULL,
    actual_repair_cost integer,
    actual_repair_completed_date date,
    repair_shop_name character varying(100),
    actual_cost_recorded_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hidden_at timestamp with time zone,
    CONSTRAINT ck_ac_car_class CHECK (((snapshot_car_class)::text = ANY ((ARRAY['CityCar'::character varying, 'Compact'::character varying, 'Mid-size'::character varying, 'Full-size'::character varying])::text[]))),
    CONSTRAINT ck_ac_cost CHECK (((actual_repair_cost IS NULL) OR (actual_repair_cost > 0))),
    CONSTRAINT ck_ac_input_type CHECK (((vehicle_input_type)::text = ANY ((ARRAY['REGISTERED'::character varying, 'DIRECT'::character varying])::text[]))),
    CONSTRAINT ck_ac_model_year CHECK (((snapshot_model_year >= 1980) AND (snapshot_model_year <= 2100))),
    CONSTRAINT ck_ac_vehicle_type CHECK (((snapshot_vehicle_type)::text = ANY ((ARRAY['SEDAN'::character varying, 'SUV'::character varying, 'VAN'::character varying, 'TRUCK'::character varying])::text[])))
);


ALTER TABLE public.accident OWNER TO jaewon;

--
-- Name: accident_accident_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.accident_accident_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.accident_accident_id_seq OWNER TO jaewon;

--
-- Name: accident_accident_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.accident_accident_id_seq OWNED BY public.accident.accident_id;


--
-- Name: accident_image; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.accident_image (
    image_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    original_filename character varying(255) NOT NULL,
    angle_code character varying(20),
    quality_status character varying(20) DEFAULT 'PASS'::character varying NOT NULL,
    quality_reason character varying(100),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_ai_quality CHECK (((quality_status)::text = ANY ((ARRAY['PASS'::character varying, 'WARN'::character varying])::text[])))
);


ALTER TABLE public.accident_image OWNER TO jaewon;

--
-- Name: accident_image_asset; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.accident_image_asset (
    asset_id bigint NOT NULL,
    image_id bigint NOT NULL,
    variant character varying(20) NOT NULL,
    s3_key character varying(500) NOT NULL,
    width smallint,
    height smallint,
    file_size integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_aia_size CHECK (((file_size IS NULL) OR (file_size <= 20971520))),
    CONSTRAINT ck_aia_var CHECK (((variant)::text = ANY ((ARRAY['ORIGINAL'::character varying, 'RESIZED'::character varying, 'THUMBNAIL'::character varying, 'BLURRED'::character varying])::text[])))
);


ALTER TABLE public.accident_image_asset OWNER TO jaewon;

--
-- Name: accident_image_asset_asset_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.accident_image_asset_asset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.accident_image_asset_asset_id_seq OWNER TO jaewon;

--
-- Name: accident_image_asset_asset_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.accident_image_asset_asset_id_seq OWNED BY public.accident_image_asset.asset_id;


--
-- Name: accident_image_image_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.accident_image_image_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.accident_image_image_id_seq OWNER TO jaewon;

--
-- Name: accident_image_image_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.accident_image_image_id_seq OWNED BY public.accident_image.image_id;


--
-- Name: accident_review; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.accident_review (
    review_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reviewed_job_id bigint,
    snapshot_actual_repair_cost integer,
    reviewer_member_id bigint,
    reject_reason character varying(500),
    queued_at timestamp with time zone DEFAULT now() NOT NULL,
    reviewed_at timestamp with time zone,
    CONSTRAINT ck_ar_cost CHECK (((snapshot_actual_repair_cost IS NULL) OR (snapshot_actual_repair_cost > 0))),
    CONSTRAINT ck_ar_done CHECK ((((status)::text = 'PENDING'::text) = (reviewed_at IS NULL))),
    CONSTRAINT ck_ar_reject CHECK ((((status)::text = 'REJECTED'::text) = (reject_reason IS NOT NULL))),
    CONSTRAINT ck_ar_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[])))
);


ALTER TABLE public.accident_review OWNER TO jaewon;

--
-- Name: accident_review_review_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.accident_review_review_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.accident_review_review_id_seq OWNER TO jaewon;

--
-- Name: accident_review_review_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.accident_review_review_id_seq OWNED BY public.accident_review.review_id;


--
-- Name: analysis_image_result; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.analysis_image_result (
    result_id bigint NOT NULL,
    job_id bigint NOT NULL,
    image_id bigint NOT NULL,
    s3_key_overlay character varying(500),
    detections jsonb,
    is_excluded boolean DEFAULT false NOT NULL,
    exclusion_reason character varying(50),
    CONSTRAINT ck_air_reason CHECK (((exclusion_reason IS NULL) OR ((exclusion_reason)::text = ANY ((ARRAY['NOT_VEHICLE'::character varying, 'RATIO_BELOW_THRESHOLD'::character varying])::text[]))))
);


ALTER TABLE public.analysis_image_result OWNER TO jaewon;

--
-- Name: analysis_image_result_result_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.analysis_image_result_result_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.analysis_image_result_result_id_seq OWNER TO jaewon;

--
-- Name: analysis_image_result_result_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.analysis_image_result_result_id_seq OWNED BY public.analysis_image_result.result_id;


--
-- Name: analysis_job; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.analysis_job (
    job_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    retry_count smallint DEFAULT 0 NOT NULL,
    failure_reason character varying(50),
    model_version character varying(50),
    request_id character varying(64),
    pipeline_version_id bigint,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    selected_part_code character varying(50),
    CONSTRAINT ck_aj_retry CHECK (((retry_count >= 0) AND (retry_count <= 3))),
    CONSTRAINT ck_aj_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.analysis_job OWNER TO jaewon;

--
-- Name: analysis_job_job_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.analysis_job_job_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.analysis_job_job_id_seq OWNER TO jaewon;

--
-- Name: analysis_job_job_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.analysis_job_job_id_seq OWNED BY public.analysis_job.job_id;


--
-- Name: analysis_stage; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.analysis_stage (
    stage_id bigint NOT NULL,
    job_id bigint NOT NULL,
    stage character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    detail character varying(100),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    CONSTRAINT ck_as_stage CHECK (((stage)::text = ANY ((ARRAY['PREPROCESS'::character varying, 'DETECT'::character varying, 'MATCH'::character varying, 'ESTIMATE'::character varying])::text[]))),
    CONSTRAINT ck_as_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.analysis_stage OWNER TO jaewon;

--
-- Name: analysis_stage_stage_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.analysis_stage_stage_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.analysis_stage_stage_id_seq OWNER TO jaewon;

--
-- Name: analysis_stage_stage_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.analysis_stage_stage_id_seq OWNED BY public.analysis_stage.stage_id;


--
-- Name: audit_log; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.audit_log (
    audit_log_id bigint NOT NULL,
    actor_member_id bigint,
    action_type character varying(50) NOT NULL,
    target_type character varying(50) NOT NULL,
    target_id character varying(100) NOT NULL,
    request_id character varying(64),
    before_data text,
    after_data text,
    change_reason character varying(500),
    ip_address character varying(45),
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.audit_log OWNER TO jaewon;

--
-- Name: audit_log_audit_log_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.audit_log_audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.audit_log_audit_log_id_seq OWNER TO jaewon;

--
-- Name: audit_log_audit_log_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.audit_log_audit_log_id_seq OWNED BY public.audit_log.audit_log_id;


--
-- Name: batch_job_execution; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.batch_job_execution (
    batch_job_execution_id bigint NOT NULL,
    job_name character varying(100) NOT NULL,
    job_version character varying(100),
    status character varying(20) DEFAULT 'RUNNING'::character varying NOT NULL,
    input_ref character varying(500),
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    summary jsonb,
    error_message text,
    CONSTRAINT ck_bje_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.batch_job_execution OWNER TO jaewon;

--
-- Name: batch_job_execution_batch_job_execution_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.batch_job_execution_batch_job_execution_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.batch_job_execution_batch_job_execution_id_seq OWNER TO jaewon;

--
-- Name: batch_job_execution_batch_job_execution_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.batch_job_execution_batch_job_execution_id_seq OWNED BY public.batch_job_execution.batch_job_execution_id;


--
-- Name: damaged_part; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.damaged_part (
    damaged_part_id bigint NOT NULL,
    job_id bigint NOT NULL,
    part_code character varying(50) NOT NULL,
    damage_type character varying(20) NOT NULL,
    repair_method character varying(20),
    severity_score numeric(6,2),
    confidence numeric(5,4) NOT NULL,
    CONSTRAINT ck_dp_damage CHECK (((damage_type)::text = ANY ((ARRAY['Scratched'::character varying, 'Separated'::character varying, 'Crushed'::character varying, 'Breakage'::character varying])::text[]))),
    CONSTRAINT ck_dp_method CHECK (((repair_method)::text = ANY ((ARRAY['coating'::character varying, 'sheet_metal'::character varying, 'exchange'::character varying, 'repair'::character varying])::text[])))
);


ALTER TABLE public.damaged_part OWNER TO jaewon;

--
-- Name: damaged_part_damaged_part_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.damaged_part_damaged_part_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.damaged_part_damaged_part_id_seq OWNER TO jaewon;

--
-- Name: damaged_part_damaged_part_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.damaged_part_damaged_part_id_seq OWNED BY public.damaged_part.damaged_part_id;


--
-- Name: data_validation_error; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.data_validation_error (
    data_validation_error_id bigint NOT NULL,
    batch_job_execution_id bigint NOT NULL,
    error_type character varying(50) NOT NULL,
    source_ref character varying(500),
    case_external_ref character varying(100),
    category_id character varying(100),
    error_detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.data_validation_error OWNER TO jaewon;

--
-- Name: data_validation_error_data_validation_error_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.data_validation_error_data_validation_error_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.data_validation_error_data_validation_error_id_seq OWNER TO jaewon;

--
-- Name: data_validation_error_data_validation_error_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.data_validation_error_data_validation_error_id_seq OWNED BY public.data_validation_error.data_validation_error_id;


--
-- Name: embedding_model_version; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.embedding_model_version (
    model_version_id bigint NOT NULL,
    model_name character varying(100) NOT NULL,
    version character varying(50) NOT NULL,
    dimension smallint NOT NULL,
    distance_metric character varying(20) DEFAULT 'cosine'::character varying NOT NULL,
    preprocessing jsonb,
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_emv_dim CHECK ((dimension = 768)),
    CONSTRAINT ck_emv_metric CHECK (((distance_metric)::text = ANY ((ARRAY['cosine'::character varying, 'l2'::character varying, 'ip'::character varying])::text[])))
);


ALTER TABLE public.embedding_model_version OWNER TO jaewon;

--
-- Name: embedding_model_version_model_version_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.embedding_model_version_model_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.embedding_model_version_model_version_id_seq OWNER TO jaewon;

--
-- Name: embedding_model_version_model_version_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.embedding_model_version_model_version_id_seq OWNED BY public.embedding_model_version.model_version_id;


--
-- Name: estimate; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate (
    estimate_id bigint NOT NULL,
    job_id bigint NOT NULL,
    version smallint DEFAULT 1 NOT NULL,
    is_estimable boolean DEFAULT true NOT NULL,
    non_estimable_reason character varying(100),
    labor_rate integer,
    total_hq numeric(7,2),
    total_min integer,
    total_median integer,
    total_max integer,
    ref_case_total integer,
    confidence_grade character varying(10),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    unresolved_parts jsonb,
    CONSTRAINT ck_est_grade CHECK (((confidence_grade IS NULL) OR ((confidence_grade)::text = ANY ((ARRAY['HIGH'::character varying, 'MEDIUM'::character varying, 'LOW'::character varying])::text[]))))
);


ALTER TABLE public.estimate OWNER TO jaewon;

--
-- Name: estimate_estimate_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_estimate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_estimate_id_seq OWNER TO jaewon;

--
-- Name: estimate_estimate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_estimate_id_seq OWNED BY public.estimate.estimate_id;


--
-- Name: estimate_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_item (
    estimate_item_id bigint NOT NULL,
    estimate_id bigint NOT NULL,
    damaged_part_id bigint NOT NULL,
    repair_method character varying(20) NOT NULL,
    standard_hq numeric(6,2),
    part_cost_median integer,
    labor_cost_median integer,
    paint_material_cost integer,
    item_min integer NOT NULL,
    item_median integer NOT NULL,
    item_max integer NOT NULL,
    ref_case_count integer NOT NULL,
    ref_condition jsonb NOT NULL,
    is_low_confidence boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_ei_method CHECK (((repair_method)::text = ANY ((ARRAY['coating'::character varying, 'sheet_metal'::character varying, 'exchange'::character varying, 'repair'::character varying])::text[])))
);


ALTER TABLE public.estimate_item OWNER TO jaewon;

--
-- Name: estimate_item_estimate_item_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_item_estimate_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_item_estimate_item_id_seq OWNER TO jaewon;

--
-- Name: estimate_item_estimate_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_item_estimate_item_id_seq OWNED BY public.estimate_item.estimate_item_id;


--
-- Name: estimate_narrative; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_narrative (
    estimate_id bigint NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    content jsonb,
    failure_reason character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_en_done CHECK ((((status)::text <> 'COMPLETED'::text) OR (content IS NOT NULL))),
    CONSTRAINT ck_en_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.estimate_narrative OWNER TO jaewon;

--
-- Name: estimate_notice; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_notice (
    code character varying(30) NOT NULL,
    message character varying(500) NOT NULL,
    display_order smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.estimate_notice OWNER TO jaewon;

--
-- Name: estimate_report; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_report (
    report_id bigint NOT NULL,
    estimate_id bigint NOT NULL,
    report_no character varying(24) NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    s3_key_pdf character varying(500),
    failure_reason character varying(200),
    retry_count smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT ck_er_done CHECK (((completed_at IS NULL) OR ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT ck_er_retry CHECK (((retry_count >= 0) AND (retry_count <= 3))),
    CONSTRAINT ck_er_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.estimate_report OWNER TO jaewon;

--
-- Name: estimate_report_report_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_report_report_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_report_report_id_seq OWNER TO jaewon;

--
-- Name: estimate_report_report_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_report_report_id_seq OWNED BY public.estimate_report.report_id;


--
-- Name: estimate_validation; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_validation (
    validation_id bigint NOT NULL,
    member_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    estimate_id bigint,
    s3_key_file character varying(500),
    file_type character varying(10) NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    claimed_total integer,
    llm_model character varying(50),
    llm_grade character varying(20),
    llm_summary text,
    failure_reason character varying(200),
    rule_version integer,
    review_item_count integer DEFAULT 0 NOT NULL,
    total_item_count integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT ck_ev_done CHECK (((completed_at IS NULL) OR ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT ck_ev_file CHECK (((((file_type)::text = 'MANUAL'::text) AND (s3_key_file IS NULL)) OR (((file_type)::text <> 'MANUAL'::text) AND (s3_key_file IS NOT NULL)))),
    CONSTRAINT ck_ev_grade CHECK (((llm_grade IS NULL) OR ((llm_grade)::text = ANY ((ARRAY['APPROPRIATE'::character varying, 'CAUTION'::character varying, 'NEEDS_REVIEW'::character varying])::text[])))),
    CONSTRAINT ck_ev_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT ck_ev_type CHECK (((file_type)::text = ANY ((ARRAY['IMAGE'::character varying, 'PDF'::character varying, 'MANUAL'::character varying])::text[])))
);


ALTER TABLE public.estimate_validation OWNER TO jaewon;

--
-- Name: estimate_validation_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_validation_item (
    validation_item_id bigint NOT NULL,
    validation_id bigint NOT NULL,
    line_no smallint NOT NULL,
    raw_item_name character varying(200) NOT NULL,
    normalized_item_name character varying(100),
    part_code character varying(50),
    work_type character varying(20),
    quantity smallint DEFAULT 1 NOT NULL,
    part_cost integer,
    labor_cost integer,
    subtotal integer,
    llm_flag character varying(30),
    llm_reason character varying(300),
    reference_min integer,
    reference_median integer,
    reference_p75 integer,
    reference_max integer,
    reference_case_count integer
);


ALTER TABLE public.estimate_validation_item OWNER TO jaewon;

--
-- Name: estimate_validation_item_validation_item_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_validation_item_validation_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_validation_item_validation_item_id_seq OWNER TO jaewon;

--
-- Name: estimate_validation_item_validation_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_validation_item_validation_item_id_seq OWNED BY public.estimate_validation_item.validation_item_id;


--
-- Name: estimate_validation_question; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_validation_question (
    question_id bigint NOT NULL,
    validation_id bigint NOT NULL,
    validation_item_id bigint,
    source_flag character varying(30) NOT NULL,
    display_order smallint NOT NULL,
    question_text character varying(500) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.estimate_validation_question OWNER TO jaewon;

--
-- Name: estimate_validation_question_question_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_validation_question_question_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_validation_question_question_id_seq OWNER TO jaewon;

--
-- Name: estimate_validation_question_question_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_validation_question_question_id_seq OWNED BY public.estimate_validation_question.question_id;


--
-- Name: estimate_validation_report; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_validation_report (
    validation_id bigint NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    s3_key_pdf character varying(500),
    failure_reason character varying(200),
    retry_count smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT ck_evr_done CHECK (((completed_at IS NULL) OR ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT ck_evr_retry CHECK (((retry_count >= 0) AND (retry_count <= 3))),
    CONSTRAINT ck_evr_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.estimate_validation_report OWNER TO jaewon;

--
-- Name: estimate_validation_rule; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.estimate_validation_rule (
    rule_version integer NOT NULL,
    reference_percentile smallint NOT NULL,
    severe_over_p75_multiplier numeric(5,2) NOT NULL,
    caution_total_difference_ratio numeric(5,4) NOT NULL,
    needs_review_total_difference_ratio numeric(5,4) NOT NULL,
    needs_review_item_count smallint NOT NULL,
    changed_by bigint,
    change_note character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_evr_caution CHECK ((caution_total_difference_ratio >= (0)::numeric)),
    CONSTRAINT ck_evr_count CHECK ((needs_review_item_count >= 1)),
    CONSTRAINT ck_evr_mult CHECK ((severe_over_p75_multiplier > 1.0)),
    CONSTRAINT ck_evr_pct CHECK (((reference_percentile >= 1) AND (reference_percentile <= 100))),
    CONSTRAINT ck_evr_review CHECK ((needs_review_total_difference_ratio >= caution_total_difference_ratio)),
    CONSTRAINT ck_evr_version CHECK ((rule_version >= 1))
);


ALTER TABLE public.estimate_validation_rule OWNER TO jaewon;

--
-- Name: estimate_validation_validation_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.estimate_validation_validation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.estimate_validation_validation_id_seq OWNER TO jaewon;

--
-- Name: estimate_validation_validation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.estimate_validation_validation_id_seq OWNED BY public.estimate_validation.validation_id;


--
-- Name: feature_pipeline_version; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.feature_pipeline_version (
    pipeline_version_id bigint NOT NULL,
    pipeline_name character varying(100) NOT NULL,
    version character varying(50) NOT NULL,
    pair_rule_version character varying(50) NOT NULL,
    pair_threshold numeric(3,2) NOT NULL,
    roi_padding_ratio numeric(3,2),
    params jsonb,
    is_active boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.feature_pipeline_version OWNER TO jaewon;

--
-- Name: feature_pipeline_version_pipeline_version_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.feature_pipeline_version_pipeline_version_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.feature_pipeline_version_pipeline_version_id_seq OWNER TO jaewon;

--
-- Name: feature_pipeline_version_pipeline_version_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.feature_pipeline_version_pipeline_version_id_seq OWNED BY public.feature_pipeline_version.pipeline_version_id;


--
-- Name: member; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.member (
    member_id bigint NOT NULL,
    provider character varying(20) NOT NULL,
    provider_user_id character varying(255) NOT NULL,
    nickname character varying(12) NOT NULL,
    email character varying(255),
    profile_image_key character varying(500),
    role character varying(20) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    withdrawn_at timestamp with time zone,
    CONSTRAINT ck_member_provider CHECK (((provider)::text = ANY ((ARRAY['KAKAO'::character varying, 'GOOGLE'::character varying])::text[]))),
    CONSTRAINT ck_member_role CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ADMIN'::character varying])::text[]))),
    CONSTRAINT ck_member_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'WITHDRAWN'::character varying])::text[])))
);


ALTER TABLE public.member OWNER TO jaewon;

--
-- Name: member_member_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.member_member_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.member_member_id_seq OWNER TO jaewon;

--
-- Name: member_member_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.member_member_id_seq OWNED BY public.member.member_id;


--
-- Name: part_code; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.part_code (
    part_code character varying(50) NOT NULL,
    name_ko character varying(50) NOT NULL,
    layout_zone character varying(20) NOT NULL,
    display_order smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    code_scope character varying(20) DEFAULT 'EXTENDED'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_pc_scope CHECK (((code_scope)::text = ANY ((ARRAY['AI_LABEL'::character varying, 'EXTENDED'::character varying])::text[]))),
    CONSTRAINT ck_pc_zone CHECK (((layout_zone)::text = ANY ((ARRAY['FRONT'::character varying, 'REAR'::character varying, 'SIDE_L'::character varying, 'SIDE_R'::character varying, 'TOP'::character varying, 'UNDER'::character varying])::text[])))
);


ALTER TABLE public.part_code OWNER TO jaewon;

--
-- Name: part_name_mapping; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.part_name_mapping (
    raw_name character varying(200) NOT NULL,
    part_code character varying(50) NOT NULL
);


ALTER TABLE public.part_name_mapping OWNER TO jaewon;

--
-- Name: repair_case; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case (
    case_id bigint NOT NULL,
    source character varying(20) NOT NULL,
    external_ref character varying(50),
    model_id bigint,
    manufacturer character varying(50),
    model_name character varying(100),
    car_class character varying(20) NOT NULL,
    model_year smallint,
    repair_year smallint,
    labor_rate integer,
    total_cost integer,
    claim_amount integer,
    paid_amount integer,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    price_tier character varying(2),
    CONSTRAINT ck_rc_class CHECK (((car_class)::text = ANY ((ARRAY['CityCar'::character varying, 'Compact'::character varying, 'Mid-size'::character varying, 'Full-size'::character varying])::text[]))),
    CONSTRAINT ck_rc_src CHECK (((source)::text = ANY ((ARRAY['AIHUB_AS'::character varying, 'AIHUB_SC'::character varying, 'SERVICE'::character varying])::text[]))),
    CONSTRAINT ck_rc_tier CHECK (((price_tier)::text = ANY ((ARRAY['P1'::character varying, 'P2'::character varying, 'P3'::character varying, 'P4'::character varying])::text[])))
);


ALTER TABLE public.repair_case OWNER TO jaewon;

--
-- Name: repair_case_case_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_case_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_case_id_seq OWNER TO jaewon;

--
-- Name: repair_case_case_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_case_id_seq OWNED BY public.repair_case.case_id;


--
-- Name: repair_case_damage_feature; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_damage_feature (
    damage_feature_id bigint NOT NULL,
    case_image_id bigint NOT NULL,
    pipeline_version_id bigint NOT NULL,
    roi_index smallint NOT NULL,
    damage_type character varying(20) NOT NULL,
    damage_polygon jsonb NOT NULL,
    roi_box jsonb NOT NULL,
    part_code character varying(50),
    pair_status character varying(20) NOT NULL,
    quality_status character varying(20) NOT NULL,
    confidence numeric(5,4),
    is_searchable boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcdf_pair CHECK (((pair_status)::text = ANY ((ARRAY['PAIRED'::character varying, 'UNPAIRED'::character varying, 'AMBIGUOUS'::character varying])::text[]))),
    CONSTRAINT ck_rcdf_type CHECK (((damage_type)::text = ANY ((ARRAY['SCRATCHED'::character varying, 'SEPARATED'::character varying, 'CRUSHED'::character varying, 'BREAKAGE'::character varying])::text[])))
);


ALTER TABLE public.repair_case_damage_feature OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_damage_feature_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_damage_feature_damage_feature_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_damage_feature_damage_feature_id_seq OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_damage_feature_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_damage_feature_damage_feature_id_seq OWNED BY public.repair_case_damage_feature.damage_feature_id;


--
-- Name: repair_case_damage_feature_part_candidate; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_damage_feature_part_candidate (
    candidate_id bigint NOT NULL,
    damage_feature_id bigint NOT NULL,
    image_part_inference_id bigint NOT NULL,
    candidate_index smallint NOT NULL,
    part_code character varying(50) NOT NULL,
    part_confidence numeric(5,4) NOT NULL,
    part_bbox jsonb NOT NULL,
    overlap_score numeric(6,5) NOT NULL,
    is_primary boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcdfpc_confidence CHECK (((part_confidence >= (0)::numeric) AND (part_confidence <= (1)::numeric))),
    CONSTRAINT ck_rcdfpc_index CHECK ((candidate_index >= 0)),
    CONSTRAINT ck_rcdfpc_overlap CHECK (((overlap_score >= (0)::numeric) AND (overlap_score <= (1)::numeric)))
);


ALTER TABLE public.repair_case_damage_feature_part_candidate OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_part_candidate_candidate_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_damage_feature_part_candidate_candidate_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_damage_feature_part_candidate_candidate_id_seq OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_part_candidate_candidate_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_damage_feature_part_candidate_candidate_id_seq OWNED BY public.repair_case_damage_feature_part_candidate.candidate_id;


--
-- Name: repair_case_damage_feature_part_hint; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_damage_feature_part_hint (
    hint_id bigint NOT NULL,
    damage_feature_id bigint NOT NULL,
    part_code character varying(50) NOT NULL,
    hint_source character varying(20) DEFAULT 'REPAIR'::character varying NOT NULL,
    raw_part_name character varying(100) NOT NULL,
    repair_methods jsonb DEFAULT '[]'::jsonb NOT NULL,
    source_annotation_ref character varying(255) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcdfph_methods CHECK ((jsonb_typeof(repair_methods) = 'array'::text)),
    CONSTRAINT ck_rcdfph_source CHECK (((hint_source)::text = 'REPAIR'::text))
);


ALTER TABLE public.repair_case_damage_feature_part_hint OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_part_hint_hint_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_damage_feature_part_hint_hint_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_damage_feature_part_hint_hint_id_seq OWNER TO jaewon;

--
-- Name: repair_case_damage_feature_part_hint_hint_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_damage_feature_part_hint_hint_id_seq OWNED BY public.repair_case_damage_feature_part_hint.hint_id;


--
-- Name: repair_case_damage_feature_part_mapping; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_damage_feature_part_mapping (
    damage_feature_id bigint NOT NULL,
    image_part_inference_id bigint NOT NULL,
    mapping_status character varying(20) NOT NULL,
    primary_candidate_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcdfpm_status CHECK (((mapping_status)::text = ANY ((ARRAY['PAIRED'::character varying, 'UNPAIRED'::character varying, 'AMBIGUOUS'::character varying])::text[])))
);


ALTER TABLE public.repair_case_damage_feature_part_mapping OWNER TO jaewon;

--
-- Name: repair_case_image; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_image (
    case_image_id bigint NOT NULL,
    case_id bigint NOT NULL,
    source_image_ref character varying(255) NOT NULL,
    storage_key character varying(500) NOT NULL,
    blur_key character varying(500),
    angle_tag character varying(20),
    image_type character varying(20) NOT NULL,
    source_dataset_split character varying(20),
    quality_status character varying(20),
    is_searchable boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcimg_source_split CHECK (((source_dataset_split IS NULL) OR ((source_dataset_split)::text = ANY ((ARRAY['TRAIN'::character varying, 'VALIDATION'::character varying])::text[])))),
    CONSTRAINT ck_rcimg_type CHECK (((image_type)::text = ANY ((ARRAY['DAMAGE'::character varying, 'DAMAGE_PART'::character varying])::text[])))
);


ALTER TABLE public.repair_case_image OWNER TO jaewon;

--
-- Name: repair_case_image_case_image_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_image_case_image_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_image_case_image_id_seq OWNER TO jaewon;

--
-- Name: repair_case_image_case_image_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_image_case_image_id_seq OWNED BY public.repair_case_image.case_image_id;


--
-- Name: repair_case_image_part_annotation; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_image_part_annotation (
    case_image_part_annotation_id bigint NOT NULL,
    case_image_id bigint NOT NULL,
    part_code character varying(50) NOT NULL,
    source_annotation_ref character varying(255) NOT NULL,
    part_polygon jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.repair_case_image_part_annotation OWNER TO jaewon;

--
-- Name: repair_case_image_part_annota_case_image_part_annotation_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_image_part_annota_case_image_part_annotation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_image_part_annota_case_image_part_annotation_id_seq OWNER TO jaewon;

--
-- Name: repair_case_image_part_annota_case_image_part_annotation_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_image_part_annota_case_image_part_annotation_id_seq OWNED BY public.repair_case_image_part_annotation.case_image_part_annotation_id;


--
-- Name: repair_case_image_part_inference; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_image_part_inference (
    image_part_inference_id bigint NOT NULL,
    case_image_id bigint NOT NULL,
    part_model_name character varying(100) NOT NULL,
    part_model_version character varying(50) NOT NULL,
    weights_sha256 character varying(64),
    run_status character varying(30) NOT NULL,
    detected_part_count integer DEFAULT 0 NOT NULL,
    raw_predictions jsonb DEFAULT '[]'::jsonb NOT NULL,
    error_code character varying(100),
    processed_at timestamp with time zone DEFAULT now() NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rcipi_count CHECK ((detected_part_count >= 0)),
    CONSTRAINT ck_rcipi_error CHECK (((((run_status)::text = 'ERROR'::text) AND (error_code IS NOT NULL)) OR (((run_status)::text <> 'ERROR'::text) AND (error_code IS NULL)))),
    CONSTRAINT ck_rcipi_status CHECK (((run_status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'PART_NOT_DETECTED'::character varying, 'ERROR'::character varying])::text[])))
);


ALTER TABLE public.repair_case_image_part_inference OWNER TO jaewon;

--
-- Name: repair_case_image_part_inference_image_part_inference_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_image_part_inference_image_part_inference_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_image_part_inference_image_part_inference_id_seq OWNER TO jaewon;

--
-- Name: repair_case_image_part_inference_image_part_inference_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_image_part_inference_image_part_inference_id_seq OWNED BY public.repair_case_image_part_inference.image_part_inference_id;


--
-- Name: repair_case_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_item (
    case_item_id bigint NOT NULL,
    case_id bigint NOT NULL,
    source_item_key character varying(100) NOT NULL,
    part_code character varying(50),
    raw_item_name character varying(500),
    line_type character varying(20) DEFAULT 'WORK'::character varying NOT NULL,
    work_type character varying(20),
    work_code character varying(30),
    assessment_status character varying(20),
    hq numeric(6,2),
    reference_part_price integer,
    part_cost integer,
    paint_material_cost integer,
    labor_cost integer,
    pre_adjustment_part_cost integer,
    pre_adjustment_labor_cost integer,
    post_adjustment_part_cost integer,
    post_adjustment_labor_cost integer,
    item_total integer,
    CONSTRAINT ck_rci_assessment CHECK (((assessment_status IS NULL) OR ((assessment_status)::text = ANY ((ARRAY['APPROVED'::character varying, 'NOT_APPROVED'::character varying])::text[])))),
    CONSTRAINT ck_rci_line_type CHECK (((line_type)::text = ANY ((ARRAY['WORK'::character varying, 'PART_PRICE'::character varying, 'REFERENCE_PRICE'::character varying, 'ANCILLARY'::character varying])::text[]))),
    CONSTRAINT ck_rci_part_code CHECK ((((line_type)::text = 'ANCILLARY'::text) OR (part_code IS NOT NULL))),
    CONSTRAINT ck_rci_work CHECK (((((line_type)::text = ANY ((ARRAY['PART_PRICE'::character varying, 'REFERENCE_PRICE'::character varying])::text[])) AND (work_type IS NULL) AND (work_code IS NULL)) OR (((line_type)::text = 'ANCILLARY'::text) AND ((work_code)::text = ANY ((ARRAY['TOWING'::character varying, 'RESCUE'::character varying])::text[]))) OR (((line_type)::text = 'WORK'::text) AND ((work_code IS NOT NULL) OR ((assessment_status)::text = 'NOT_APPROVED'::text))))),
    CONSTRAINT ck_rci_work_code CHECK (((work_code IS NULL) OR ((work_code)::text = ANY ((ARRAY['COATING'::character varying, 'REPAIR'::character varying, 'SHEET_METAL'::character varying, 'EXCHANGE'::character varying, 'REMOVE_INSTALL'::character varying, 'OVERHAUL'::character varying, 'OVERHAUL_HALF'::character varying, 'OVERHAUL_THIRD'::character varying, 'OVERHAUL_QUARTER'::character varying, 'ADJUSTMENT'::character varying, 'TOWING'::character varying, 'RESCUE'::character varying])::text[]))))
);


ALTER TABLE public.repair_case_item OWNER TO jaewon;

--
-- Name: repair_case_item_case_item_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_item_case_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_item_case_item_id_seq OWNER TO jaewon;

--
-- Name: repair_case_item_case_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_item_case_item_id_seq OWNED BY public.repair_case_item.case_item_id;


--
-- Name: repair_case_roi_embedding; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_case_roi_embedding (
    roi_embedding_id bigint NOT NULL,
    case_image_id bigint NOT NULL,
    model_version_id bigint NOT NULL,
    damage_feature_id bigint NOT NULL,
    confidence numeric(5,4),
    embedding public.vector(768) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.repair_case_roi_embedding OWNER TO jaewon;

--
-- Name: repair_case_roi_embedding_roi_embedding_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_case_roi_embedding_roi_embedding_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_case_roi_embedding_roi_embedding_id_seq OWNER TO jaewon;

--
-- Name: repair_case_roi_embedding_roi_embedding_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_case_roi_embedding_roi_embedding_id_seq OWNED BY public.repair_case_roi_embedding.roi_embedding_id;


--
-- Name: repair_checklist; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_checklist (
    checklist_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    generation_no smallint DEFAULT 1 NOT NULL,
    failure_reason character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    regenerated_at timestamp with time zone,
    summary character varying(300),
    CONSTRAINT ck_rcl_done CHECK (((completed_at IS NULL) OR ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT ck_rcl_generation CHECK ((generation_no >= 1)),
    CONSTRAINT ck_rcl_regen CHECK (((regenerated_at IS NULL) OR (generation_no > 1))),
    CONSTRAINT ck_rcl_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.repair_checklist OWNER TO jaewon;

--
-- Name: repair_checklist_checklist_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_checklist_checklist_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_checklist_checklist_id_seq OWNER TO jaewon;

--
-- Name: repair_checklist_checklist_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_checklist_checklist_id_seq OWNED BY public.repair_checklist.checklist_id;


--
-- Name: repair_checklist_common_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_checklist_common_item (
    code character varying(30) NOT NULL,
    message character varying(500) NOT NULL,
    display_order smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.repair_checklist_common_item OWNER TO jaewon;

--
-- Name: repair_checklist_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_checklist_item (
    item_id bigint NOT NULL,
    checklist_id bigint NOT NULL,
    source character varying(20) NOT NULL,
    common_code character varying(30),
    content character varying(500) NOT NULL,
    is_checked boolean DEFAULT false NOT NULL,
    memo character varying(500),
    display_order smallint DEFAULT 0 NOT NULL,
    checked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    category character varying(10) DEFAULT 'PART'::character varying NOT NULL,
    part_code character varying(50),
    reason character varying(300),
    CONSTRAINT ck_rcli_category CHECK (((category)::text = ANY ((ARRAY['COMMON'::character varying, 'PART'::character varying, 'HIDDEN'::character varying])::text[]))),
    CONSTRAINT ck_rcli_checked CHECK (((checked_at IS NULL) OR (is_checked = true))),
    CONSTRAINT ck_rcli_link CHECK (((((source)::text = 'COMMON'::text) AND (common_code IS NOT NULL)) OR (((source)::text <> 'COMMON'::text) AND (common_code IS NULL)))),
    CONSTRAINT ck_rcli_source CHECK (((source)::text = ANY ((ARRAY['AI'::character varying, 'COMMON'::character varying, 'USER'::character varying])::text[])))
);


ALTER TABLE public.repair_checklist_item OWNER TO jaewon;

--
-- Name: repair_checklist_item_item_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_checklist_item_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_checklist_item_item_id_seq OWNER TO jaewon;

--
-- Name: repair_checklist_item_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_checklist_item_item_id_seq OWNED BY public.repair_checklist_item.item_id;


--
-- Name: repair_code; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_code (
    code_type character varying(20) NOT NULL,
    code character varying(20) NOT NULL,
    display_name character varying(50) NOT NULL,
    display_order smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_rcode_type CHECK (((code_type)::text = ANY ((ARRAY['REPAIR_METHOD'::character varying, 'DAMAGE_TYPE'::character varying])::text[])))
);


ALTER TABLE public.repair_code OWNER TO jaewon;

--
-- Name: repair_cost_stat; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_cost_stat (
    stat_id bigint NOT NULL,
    car_class character varying(20) NOT NULL,
    part_code character varying(50) NOT NULL,
    damage_type character varying(20) NOT NULL,
    repair_method character varying(20) NOT NULL,
    source character varying(20) NOT NULL,
    case_count integer NOT NULL,
    hq_median numeric(6,2),
    cost_min integer NOT NULL,
    cost_p25 integer NOT NULL,
    cost_median integer NOT NULL,
    cost_p75 integer NOT NULL,
    cost_max integer NOT NULL,
    part_cost_median integer,
    labor_cost_median integer,
    aggregated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.repair_cost_stat OWNER TO jaewon;

--
-- Name: repair_cost_stat_stat_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_cost_stat_stat_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_cost_stat_stat_id_seq OWNER TO jaewon;

--
-- Name: repair_cost_stat_stat_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_cost_stat_stat_id_seq OWNED BY public.repair_cost_stat.stat_id;


--
-- Name: repair_method_rule; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_method_rule (
    rule_id bigint NOT NULL,
    damage_type character varying(20) NOT NULL,
    part_code character varying(50),
    severity_min numeric(6,2) NOT NULL,
    severity_max numeric(6,2) NOT NULL,
    max_inclusive boolean DEFAULT false NOT NULL,
    repair_method character varying(20) NOT NULL,
    priority smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rmr_damage CHECK (((damage_type)::text = ANY ((ARRAY['Scratched'::character varying, 'Separated'::character varying, 'Crushed'::character varying, 'Breakage'::character varying])::text[]))),
    CONSTRAINT ck_rmr_method CHECK (((repair_method)::text = ANY ((ARRAY['coating'::character varying, 'sheet_metal'::character varying, 'exchange'::character varying, 'repair'::character varying])::text[]))),
    CONSTRAINT ck_rmr_range CHECK ((severity_min < severity_max))
);


ALTER TABLE public.repair_method_rule OWNER TO jaewon;

--
-- Name: repair_method_rule_rule_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_method_rule_rule_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_method_rule_rule_id_seq OWNER TO jaewon;

--
-- Name: repair_method_rule_rule_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_method_rule_rule_id_seq OWNED BY public.repair_method_rule.rule_id;


--
-- Name: repair_question; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_question (
    question_id bigint NOT NULL,
    accident_id bigint NOT NULL,
    status character varying(20) DEFAULT 'QUEUED'::character varying NOT NULL,
    generation_no smallint DEFAULT 1 NOT NULL,
    failure_reason character varying(200),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    regenerated_at timestamp with time zone,
    CONSTRAINT ck_rq_done CHECK (((completed_at IS NULL) OR ((status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT ck_rq_generation CHECK ((generation_no >= 1)),
    CONSTRAINT ck_rq_regen CHECK (((regenerated_at IS NULL) OR (generation_no > 1))),
    CONSTRAINT ck_rq_status CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.repair_question OWNER TO jaewon;

--
-- Name: repair_question_item; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.repair_question_item (
    item_id bigint NOT NULL,
    question_id bigint NOT NULL,
    source character varying(20) NOT NULL,
    content character varying(500) NOT NULL,
    part_code character varying(50),
    snapshot_part_name character varying(50),
    damage_type character varying(20),
    repair_method character varying(20),
    display_order smallint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_rqi_basis CHECK (((part_code IS NULL) = (snapshot_part_name IS NULL))),
    CONSTRAINT ck_rqi_damage CHECK (((damage_type)::text = ANY ((ARRAY['Scratched'::character varying, 'Separated'::character varying, 'Crushed'::character varying, 'Breakage'::character varying])::text[]))),
    CONSTRAINT ck_rqi_method CHECK (((repair_method)::text = ANY ((ARRAY['coating'::character varying, 'sheet_metal'::character varying, 'exchange'::character varying, 'repair'::character varying])::text[]))),
    CONSTRAINT ck_rqi_part CHECK (((part_code IS NOT NULL) OR ((damage_type IS NULL) AND (repair_method IS NULL)))),
    CONSTRAINT ck_rqi_source CHECK (((source)::text = ANY ((ARRAY['AI'::character varying, 'USER'::character varying])::text[])))
);


ALTER TABLE public.repair_question_item OWNER TO jaewon;

--
-- Name: repair_question_item_item_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_question_item_item_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_question_item_item_id_seq OWNER TO jaewon;

--
-- Name: repair_question_item_item_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_question_item_item_id_seq OWNED BY public.repair_question_item.item_id;


--
-- Name: repair_question_question_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.repair_question_question_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.repair_question_question_id_seq OWNER TO jaewon;

--
-- Name: repair_question_question_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.repair_question_question_id_seq OWNED BY public.repair_question.question_id;


--
-- Name: terms_agreement; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.terms_agreement (
    agreement_id bigint NOT NULL,
    member_id bigint NOT NULL,
    terms_type character varying(20) NOT NULL,
    version character varying(20) NOT NULL,
    agreed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_terms_type CHECK (((terms_type)::text = ANY ((ARRAY['SERVICE'::character varying, 'PRIVACY'::character varying])::text[])))
);


ALTER TABLE public.terms_agreement OWNER TO jaewon;

--
-- Name: terms_agreement_agreement_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.terms_agreement_agreement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.terms_agreement_agreement_id_seq OWNER TO jaewon;

--
-- Name: terms_agreement_agreement_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.terms_agreement_agreement_id_seq OWNED BY public.terms_agreement.agreement_id;


--
-- Name: vehicle; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.vehicle (
    vehicle_id bigint NOT NULL,
    member_id bigint NOT NULL,
    model_id bigint NOT NULL,
    model_year smallint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT ck_v_year CHECK (((model_year >= 1980) AND (model_year <= 2100)))
);


ALTER TABLE public.vehicle OWNER TO jaewon;

--
-- Name: vehicle_model; Type: TABLE; Schema: public; Owner: jaewon
--

CREATE TABLE public.vehicle_model (
    model_id bigint NOT NULL,
    manufacturer character varying(50) NOT NULL,
    model_name character varying(100) NOT NULL,
    vehicle_type character varying(20) NOT NULL,
    car_class character varying(20) NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    price_tier character varying(2),
    CONSTRAINT ck_vm_class CHECK (((car_class)::text = ANY ((ARRAY['CityCar'::character varying, 'Compact'::character varying, 'Mid-size'::character varying, 'Full-size'::character varying])::text[]))),
    CONSTRAINT ck_vm_tier CHECK (((price_tier)::text = ANY ((ARRAY['P1'::character varying, 'P2'::character varying, 'P3'::character varying, 'P4'::character varying])::text[]))),
    CONSTRAINT ck_vm_type CHECK (((vehicle_type)::text = ANY ((ARRAY['SEDAN'::character varying, 'SUV'::character varying, 'VAN'::character varying, 'TRUCK'::character varying])::text[])))
);


ALTER TABLE public.vehicle_model OWNER TO jaewon;

--
-- Name: vehicle_model_model_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.vehicle_model_model_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.vehicle_model_model_id_seq OWNER TO jaewon;

--
-- Name: vehicle_model_model_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.vehicle_model_model_id_seq OWNED BY public.vehicle_model.model_id;


--
-- Name: vehicle_vehicle_id_seq; Type: SEQUENCE; Schema: public; Owner: jaewon
--

CREATE SEQUENCE public.vehicle_vehicle_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.vehicle_vehicle_id_seq OWNER TO jaewon;

--
-- Name: vehicle_vehicle_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: jaewon
--

ALTER SEQUENCE public.vehicle_vehicle_id_seq OWNED BY public.vehicle.vehicle_id;


--
-- Name: accident accident_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident ALTER COLUMN accident_id SET DEFAULT nextval('public.accident_accident_id_seq'::regclass);


--
-- Name: accident_image image_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image ALTER COLUMN image_id SET DEFAULT nextval('public.accident_image_image_id_seq'::regclass);


--
-- Name: accident_image_asset asset_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image_asset ALTER COLUMN asset_id SET DEFAULT nextval('public.accident_image_asset_asset_id_seq'::regclass);


--
-- Name: accident_review review_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review ALTER COLUMN review_id SET DEFAULT nextval('public.accident_review_review_id_seq'::regclass);


--
-- Name: analysis_image_result result_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_image_result ALTER COLUMN result_id SET DEFAULT nextval('public.analysis_image_result_result_id_seq'::regclass);


--
-- Name: analysis_job job_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_job ALTER COLUMN job_id SET DEFAULT nextval('public.analysis_job_job_id_seq'::regclass);


--
-- Name: analysis_stage stage_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_stage ALTER COLUMN stage_id SET DEFAULT nextval('public.analysis_stage_stage_id_seq'::regclass);


--
-- Name: audit_log audit_log_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.audit_log ALTER COLUMN audit_log_id SET DEFAULT nextval('public.audit_log_audit_log_id_seq'::regclass);


--
-- Name: batch_job_execution batch_job_execution_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.batch_job_execution ALTER COLUMN batch_job_execution_id SET DEFAULT nextval('public.batch_job_execution_batch_job_execution_id_seq'::regclass);


--
-- Name: damaged_part damaged_part_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.damaged_part ALTER COLUMN damaged_part_id SET DEFAULT nextval('public.damaged_part_damaged_part_id_seq'::regclass);


--
-- Name: data_validation_error data_validation_error_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.data_validation_error ALTER COLUMN data_validation_error_id SET DEFAULT nextval('public.data_validation_error_data_validation_error_id_seq'::regclass);


--
-- Name: embedding_model_version model_version_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.embedding_model_version ALTER COLUMN model_version_id SET DEFAULT nextval('public.embedding_model_version_model_version_id_seq'::regclass);


--
-- Name: estimate estimate_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate ALTER COLUMN estimate_id SET DEFAULT nextval('public.estimate_estimate_id_seq'::regclass);


--
-- Name: estimate_item estimate_item_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_item ALTER COLUMN estimate_item_id SET DEFAULT nextval('public.estimate_item_estimate_item_id_seq'::regclass);


--
-- Name: estimate_report report_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_report ALTER COLUMN report_id SET DEFAULT nextval('public.estimate_report_report_id_seq'::regclass);


--
-- Name: estimate_validation validation_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation ALTER COLUMN validation_id SET DEFAULT nextval('public.estimate_validation_validation_id_seq'::regclass);


--
-- Name: estimate_validation_item validation_item_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_item ALTER COLUMN validation_item_id SET DEFAULT nextval('public.estimate_validation_item_validation_item_id_seq'::regclass);


--
-- Name: estimate_validation_question question_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question ALTER COLUMN question_id SET DEFAULT nextval('public.estimate_validation_question_question_id_seq'::regclass);


--
-- Name: feature_pipeline_version pipeline_version_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.feature_pipeline_version ALTER COLUMN pipeline_version_id SET DEFAULT nextval('public.feature_pipeline_version_pipeline_version_id_seq'::regclass);


--
-- Name: member member_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.member ALTER COLUMN member_id SET DEFAULT nextval('public.member_member_id_seq'::regclass);


--
-- Name: repair_case case_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case ALTER COLUMN case_id SET DEFAULT nextval('public.repair_case_case_id_seq'::regclass);


--
-- Name: repair_case_damage_feature damage_feature_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature ALTER COLUMN damage_feature_id SET DEFAULT nextval('public.repair_case_damage_feature_damage_feature_id_seq'::regclass);


--
-- Name: repair_case_damage_feature_part_candidate candidate_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate ALTER COLUMN candidate_id SET DEFAULT nextval('public.repair_case_damage_feature_part_candidate_candidate_id_seq'::regclass);


--
-- Name: repair_case_damage_feature_part_hint hint_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_hint ALTER COLUMN hint_id SET DEFAULT nextval('public.repair_case_damage_feature_part_hint_hint_id_seq'::regclass);


--
-- Name: repair_case_image case_image_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image ALTER COLUMN case_image_id SET DEFAULT nextval('public.repair_case_image_case_image_id_seq'::regclass);


--
-- Name: repair_case_image_part_annotation case_image_part_annotation_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_annotation ALTER COLUMN case_image_part_annotation_id SET DEFAULT nextval('public.repair_case_image_part_annota_case_image_part_annotation_id_seq'::regclass);


--
-- Name: repair_case_image_part_inference image_part_inference_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_inference ALTER COLUMN image_part_inference_id SET DEFAULT nextval('public.repair_case_image_part_inference_image_part_inference_id_seq'::regclass);


--
-- Name: repair_case_item case_item_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_item ALTER COLUMN case_item_id SET DEFAULT nextval('public.repair_case_item_case_item_id_seq'::regclass);


--
-- Name: repair_case_roi_embedding roi_embedding_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding ALTER COLUMN roi_embedding_id SET DEFAULT nextval('public.repair_case_roi_embedding_roi_embedding_id_seq'::regclass);


--
-- Name: repair_checklist checklist_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist ALTER COLUMN checklist_id SET DEFAULT nextval('public.repair_checklist_checklist_id_seq'::regclass);


--
-- Name: repair_checklist_item item_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item ALTER COLUMN item_id SET DEFAULT nextval('public.repair_checklist_item_item_id_seq'::regclass);


--
-- Name: repair_cost_stat stat_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_cost_stat ALTER COLUMN stat_id SET DEFAULT nextval('public.repair_cost_stat_stat_id_seq'::regclass);


--
-- Name: repair_method_rule rule_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_method_rule ALTER COLUMN rule_id SET DEFAULT nextval('public.repair_method_rule_rule_id_seq'::regclass);


--
-- Name: repair_question question_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question ALTER COLUMN question_id SET DEFAULT nextval('public.repair_question_question_id_seq'::regclass);


--
-- Name: repair_question_item item_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question_item ALTER COLUMN item_id SET DEFAULT nextval('public.repair_question_item_item_id_seq'::regclass);


--
-- Name: terms_agreement agreement_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.terms_agreement ALTER COLUMN agreement_id SET DEFAULT nextval('public.terms_agreement_agreement_id_seq'::regclass);


--
-- Name: vehicle vehicle_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle ALTER COLUMN vehicle_id SET DEFAULT nextval('public.vehicle_vehicle_id_seq'::regclass);


--
-- Name: vehicle_model model_id; Type: DEFAULT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle_model ALTER COLUMN model_id SET DEFAULT nextval('public.vehicle_model_model_id_seq'::regclass);


--
-- Name: accident_image_asset accident_image_asset_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image_asset
    ADD CONSTRAINT accident_image_asset_pkey PRIMARY KEY (asset_id);


--
-- Name: accident_image accident_image_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image
    ADD CONSTRAINT accident_image_pkey PRIMARY KEY (image_id);


--
-- Name: accident accident_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident
    ADD CONSTRAINT accident_pkey PRIMARY KEY (accident_id);


--
-- Name: accident_review accident_review_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review
    ADD CONSTRAINT accident_review_pkey PRIMARY KEY (review_id);


--
-- Name: analysis_image_result analysis_image_result_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_image_result
    ADD CONSTRAINT analysis_image_result_pkey PRIMARY KEY (result_id);


--
-- Name: analysis_job analysis_job_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_job
    ADD CONSTRAINT analysis_job_pkey PRIMARY KEY (job_id);


--
-- Name: analysis_stage analysis_stage_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_stage
    ADD CONSTRAINT analysis_stage_pkey PRIMARY KEY (stage_id);


--
-- Name: audit_log audit_log_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (audit_log_id);


--
-- Name: batch_job_execution batch_job_execution_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.batch_job_execution
    ADD CONSTRAINT batch_job_execution_pkey PRIMARY KEY (batch_job_execution_id);


--
-- Name: damaged_part damaged_part_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.damaged_part
    ADD CONSTRAINT damaged_part_pkey PRIMARY KEY (damaged_part_id);


--
-- Name: data_validation_error data_validation_error_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.data_validation_error
    ADD CONSTRAINT data_validation_error_pkey PRIMARY KEY (data_validation_error_id);


--
-- Name: embedding_model_version embedding_model_version_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.embedding_model_version
    ADD CONSTRAINT embedding_model_version_pkey PRIMARY KEY (model_version_id);


--
-- Name: estimate_item estimate_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_item
    ADD CONSTRAINT estimate_item_pkey PRIMARY KEY (estimate_item_id);


--
-- Name: estimate_narrative estimate_narrative_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_narrative
    ADD CONSTRAINT estimate_narrative_pkey PRIMARY KEY (estimate_id);


--
-- Name: estimate_notice estimate_notice_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_notice
    ADD CONSTRAINT estimate_notice_pkey PRIMARY KEY (code);


--
-- Name: estimate estimate_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate
    ADD CONSTRAINT estimate_pkey PRIMARY KEY (estimate_id);


--
-- Name: estimate_report estimate_report_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_report
    ADD CONSTRAINT estimate_report_pkey PRIMARY KEY (report_id);


--
-- Name: estimate_validation_item estimate_validation_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_item
    ADD CONSTRAINT estimate_validation_item_pkey PRIMARY KEY (validation_item_id);


--
-- Name: estimate_validation estimate_validation_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT estimate_validation_pkey PRIMARY KEY (validation_id);


--
-- Name: estimate_validation_question estimate_validation_question_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question
    ADD CONSTRAINT estimate_validation_question_pkey PRIMARY KEY (question_id);


--
-- Name: estimate_validation_report estimate_validation_report_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_report
    ADD CONSTRAINT estimate_validation_report_pkey PRIMARY KEY (validation_id);


--
-- Name: estimate_validation_rule estimate_validation_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_rule
    ADD CONSTRAINT estimate_validation_rule_pkey PRIMARY KEY (rule_version);


--
-- Name: feature_pipeline_version feature_pipeline_version_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.feature_pipeline_version
    ADD CONSTRAINT feature_pipeline_version_pkey PRIMARY KEY (pipeline_version_id);


--
-- Name: member member_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.member
    ADD CONSTRAINT member_pkey PRIMARY KEY (member_id);


--
-- Name: part_code part_code_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.part_code
    ADD CONSTRAINT part_code_pkey PRIMARY KEY (part_code);


--
-- Name: part_name_mapping part_name_mapping_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.part_name_mapping
    ADD CONSTRAINT part_name_mapping_pkey PRIMARY KEY (raw_name);


--
-- Name: repair_case_damage_feature_part_mapping pk_rcdfpm; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_mapping
    ADD CONSTRAINT pk_rcdfpm PRIMARY KEY (damage_feature_id, image_part_inference_id);


--
-- Name: repair_case_damage_feature_part_candidate repair_case_damage_feature_part_candidate_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate
    ADD CONSTRAINT repair_case_damage_feature_part_candidate_pkey PRIMARY KEY (candidate_id);


--
-- Name: repair_case_damage_feature_part_hint repair_case_damage_feature_part_hint_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_hint
    ADD CONSTRAINT repair_case_damage_feature_part_hint_pkey PRIMARY KEY (hint_id);


--
-- Name: repair_case_damage_feature repair_case_damage_feature_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature
    ADD CONSTRAINT repair_case_damage_feature_pkey PRIMARY KEY (damage_feature_id);


--
-- Name: repair_case_image_part_annotation repair_case_image_part_annotation_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_annotation
    ADD CONSTRAINT repair_case_image_part_annotation_pkey PRIMARY KEY (case_image_part_annotation_id);


--
-- Name: repair_case_image_part_inference repair_case_image_part_inference_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_inference
    ADD CONSTRAINT repair_case_image_part_inference_pkey PRIMARY KEY (image_part_inference_id);


--
-- Name: repair_case_image repair_case_image_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image
    ADD CONSTRAINT repair_case_image_pkey PRIMARY KEY (case_image_id);


--
-- Name: repair_case_item repair_case_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_item
    ADD CONSTRAINT repair_case_item_pkey PRIMARY KEY (case_item_id);


--
-- Name: repair_case repair_case_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case
    ADD CONSTRAINT repair_case_pkey PRIMARY KEY (case_id);


--
-- Name: repair_case_roi_embedding repair_case_roi_embedding_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding
    ADD CONSTRAINT repair_case_roi_embedding_pkey PRIMARY KEY (roi_embedding_id);


--
-- Name: repair_checklist_common_item repair_checklist_common_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_common_item
    ADD CONSTRAINT repair_checklist_common_item_pkey PRIMARY KEY (code);


--
-- Name: repair_checklist_item repair_checklist_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item
    ADD CONSTRAINT repair_checklist_item_pkey PRIMARY KEY (item_id);


--
-- Name: repair_checklist repair_checklist_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist
    ADD CONSTRAINT repair_checklist_pkey PRIMARY KEY (checklist_id);


--
-- Name: repair_code repair_code_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_code
    ADD CONSTRAINT repair_code_pkey PRIMARY KEY (code_type, code);


--
-- Name: repair_cost_stat repair_cost_stat_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_cost_stat
    ADD CONSTRAINT repair_cost_stat_pkey PRIMARY KEY (stat_id);


--
-- Name: repair_method_rule repair_method_rule_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_method_rule
    ADD CONSTRAINT repair_method_rule_pkey PRIMARY KEY (rule_id);


--
-- Name: repair_question_item repair_question_item_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question_item
    ADD CONSTRAINT repair_question_item_pkey PRIMARY KEY (item_id);


--
-- Name: repair_question repair_question_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question
    ADD CONSTRAINT repair_question_pkey PRIMARY KEY (question_id);


--
-- Name: terms_agreement terms_agreement_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.terms_agreement
    ADD CONSTRAINT terms_agreement_pkey PRIMARY KEY (agreement_id);


--
-- Name: accident_image_asset uk_aia; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image_asset
    ADD CONSTRAINT uk_aia UNIQUE (image_id, variant);


--
-- Name: analysis_image_result uk_air; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_image_result
    ADD CONSTRAINT uk_air UNIQUE (job_id, image_id);


--
-- Name: accident_review uk_ar_accident; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review
    ADD CONSTRAINT uk_ar_accident UNIQUE (accident_id);


--
-- Name: analysis_stage uk_as; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_stage
    ADD CONSTRAINT uk_as UNIQUE (job_id, stage);


--
-- Name: damaged_part uk_dp; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.damaged_part
    ADD CONSTRAINT uk_dp UNIQUE (job_id, part_code);


--
-- Name: embedding_model_version uk_emv; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.embedding_model_version
    ADD CONSTRAINT uk_emv UNIQUE (model_name, version);


--
-- Name: estimate_report uk_er_no; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_report
    ADD CONSTRAINT uk_er_no UNIQUE (report_no);


--
-- Name: estimate uk_est; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate
    ADD CONSTRAINT uk_est UNIQUE (job_id, version);


--
-- Name: estimate_validation_item uk_evi; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_item
    ADD CONSTRAINT uk_evi UNIQUE (validation_id, line_no);


--
-- Name: estimate_validation_question uk_evq_item_flag; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question
    ADD CONSTRAINT uk_evq_item_flag UNIQUE (validation_item_id, source_flag);


--
-- Name: estimate_validation_question uk_evq_order; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question
    ADD CONSTRAINT uk_evq_order UNIQUE (validation_id, display_order);


--
-- Name: feature_pipeline_version uk_fpv; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.feature_pipeline_version
    ADD CONSTRAINT uk_fpv UNIQUE (pipeline_name, version);


--
-- Name: member uk_member_provider; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.member
    ADD CONSTRAINT uk_member_provider UNIQUE (provider, provider_user_id);


--
-- Name: repair_case uk_rc; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case
    ADD CONSTRAINT uk_rc UNIQUE (source, external_ref);


--
-- Name: repair_case_damage_feature uk_rcdf; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature
    ADD CONSTRAINT uk_rcdf UNIQUE (case_image_id, pipeline_version_id, roi_index);


--
-- Name: repair_case_damage_feature_part_candidate uk_rcdfpc_candidate; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate
    ADD CONSTRAINT uk_rcdfpc_candidate UNIQUE (damage_feature_id, image_part_inference_id, candidate_index);


--
-- Name: repair_case_damage_feature_part_hint uk_rcdfph_source; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_hint
    ADD CONSTRAINT uk_rcdfph_source UNIQUE (damage_feature_id, part_code, hint_source, source_annotation_ref);


--
-- Name: repair_case_item uk_rci_source_item; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_item
    ADD CONSTRAINT uk_rci_source_item UNIQUE (case_id, source_item_key);


--
-- Name: repair_case_image uk_rci_src; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image
    ADD CONSTRAINT uk_rci_src UNIQUE (source_image_ref);


--
-- Name: repair_case_image_part_annotation uk_rcipa_source; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_annotation
    ADD CONSTRAINT uk_rcipa_source UNIQUE (case_image_id, source_annotation_ref);


--
-- Name: repair_case_image_part_inference uk_rcipi_model; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_inference
    ADD CONSTRAINT uk_rcipi_model UNIQUE (case_image_id, part_model_name, part_model_version);


--
-- Name: repair_checklist uk_rcl_accident; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist
    ADD CONSTRAINT uk_rcl_accident UNIQUE (accident_id);


--
-- Name: repair_checklist_item uk_rcli_common; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item
    ADD CONSTRAINT uk_rcli_common UNIQUE (checklist_id, common_code);


--
-- Name: repair_cost_stat uk_rcs; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_cost_stat
    ADD CONSTRAINT uk_rcs UNIQUE (car_class, part_code, damage_type, repair_method, source);


--
-- Name: repair_case_roi_embedding uk_roi; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding
    ADD CONSTRAINT uk_roi UNIQUE (damage_feature_id, model_version_id);


--
-- Name: repair_question uk_rq_accident; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question
    ADD CONSTRAINT uk_rq_accident UNIQUE (accident_id);


--
-- Name: vehicle_model uk_vm; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle_model
    ADD CONSTRAINT uk_vm UNIQUE (manufacturer, model_name);


--
-- Name: vehicle_model vehicle_model_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle_model
    ADD CONSTRAINT vehicle_model_pkey PRIMARY KEY (model_id);


--
-- Name: vehicle vehicle_pkey; Type: CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle
    ADD CONSTRAINT vehicle_pkey PRIMARY KEY (vehicle_id);


--
-- Name: ix_accident_vehicle; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_accident_vehicle ON public.accident USING btree (vehicle_id, created_at DESC);


--
-- Name: ix_ar_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_ar_queue ON public.accident_review USING btree (status, queued_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: ix_audit_action; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_audit_action ON public.audit_log USING btree (action_type, created_at DESC);


--
-- Name: ix_audit_actor; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_audit_actor ON public.audit_log USING btree (actor_member_id, created_at DESC);


--
-- Name: ix_audit_created; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_audit_created ON public.audit_log USING btree (created_at DESC);


--
-- Name: ix_audit_target; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_audit_target ON public.audit_log USING btree (target_type, target_id, created_at DESC);


--
-- Name: ix_dve_batch; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_dve_batch ON public.data_validation_error USING btree (batch_job_execution_id, created_at);


--
-- Name: ix_dve_case_ref; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_dve_case_ref ON public.data_validation_error USING btree (case_external_ref);


--
-- Name: ix_dve_type; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_dve_type ON public.data_validation_error USING btree (error_type, created_at);


--
-- Name: ix_ei_estimate; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_ei_estimate ON public.estimate_item USING btree (estimate_id);


--
-- Name: ix_en_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_en_queue ON public.estimate_narrative USING btree (updated_at, estimate_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ix_er_estimate; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_er_estimate ON public.estimate_report USING btree (estimate_id, created_at DESC);


--
-- Name: ix_er_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_er_queue ON public.estimate_report USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ix_ev_accident; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_ev_accident ON public.estimate_validation USING btree (accident_id, created_at DESC);


--
-- Name: ix_ev_member; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_ev_member ON public.estimate_validation USING btree (member_id, created_at DESC);


--
-- Name: ix_ev_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_ev_queue ON public.estimate_validation USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ix_evr_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_evr_queue ON public.estimate_validation_report USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ix_img_accident; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_img_accident ON public.accident_image USING btree (accident_id);


--
-- Name: ix_job_accident; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_job_accident ON public.analysis_job USING btree (accident_id, created_at DESC);


--
-- Name: ix_job_queue; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_job_queue ON public.analysis_job USING btree (status, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ix_rc_class; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rc_class ON public.repair_case USING btree (car_class);


--
-- Name: ix_rc_model; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rc_model ON public.repair_case USING btree (model_id) WHERE (model_id IS NOT NULL);


--
-- Name: ix_rc_tier; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rc_tier ON public.repair_case USING btree (price_tier) WHERE (price_tier IS NOT NULL);


--
-- Name: ix_rcdf_damage; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdf_damage ON public.repair_case_damage_feature USING btree (damage_type, pipeline_version_id) WHERE is_searchable;


--
-- Name: ix_rcdf_image; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdf_image ON public.repair_case_damage_feature USING btree (case_image_id);


--
-- Name: ix_rcdf_part; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdf_part ON public.repair_case_damage_feature USING btree (part_code, damage_type) WHERE is_searchable;


--
-- Name: ix_rcdfpc_inference; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdfpc_inference ON public.repair_case_damage_feature_part_candidate USING btree (image_part_inference_id);


--
-- Name: ix_rcdfpc_part; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdfpc_part ON public.repair_case_damage_feature_part_candidate USING btree (part_code);


--
-- Name: ix_rcdfph_feature; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdfph_feature ON public.repair_case_damage_feature_part_hint USING btree (damage_feature_id);


--
-- Name: ix_rcdfph_part; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdfph_part ON public.repair_case_damage_feature_part_hint USING btree (part_code);


--
-- Name: ix_rcdfpm_inference_status; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcdfpm_inference_status ON public.repair_case_damage_feature_part_mapping USING btree (image_part_inference_id, mapping_status);


--
-- Name: ix_rci_case; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rci_case ON public.repair_case_item USING btree (case_id);


--
-- Name: ix_rci_search; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rci_search ON public.repair_case_item USING btree (part_code, work_type);


--
-- Name: ix_rcimg_case; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcimg_case ON public.repair_case_image USING btree (case_id);


--
-- Name: ix_rcipa_image; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcipa_image ON public.repair_case_image_part_annotation USING btree (case_image_id);


--
-- Name: ix_rcipi_image_status; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcipi_image_status ON public.repair_case_image_part_inference USING btree (case_image_id, run_status);


--
-- Name: ix_rcli_checklist; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rcli_checklist ON public.repair_checklist_item USING btree (checklist_id, display_order);


--
-- Name: ix_rmr_lookup; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rmr_lookup ON public.repair_method_rule USING btree (damage_type, is_active, priority);


--
-- Name: ix_roi_hnsw; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_roi_hnsw ON public.repair_case_roi_embedding USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: ix_rqi_question; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_rqi_question ON public.repair_question_item USING btree (question_id, display_order);


--
-- Name: ix_vehicle_member; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE INDEX ix_vehicle_member ON public.vehicle USING btree (member_id) WHERE (deleted_at IS NULL);


--
-- Name: ux_aj_request; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE UNIQUE INDEX ux_aj_request ON public.analysis_job USING btree (request_id) WHERE (request_id IS NOT NULL);


--
-- Name: ux_emv_active; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE UNIQUE INDEX ux_emv_active ON public.embedding_model_version USING btree (is_active) WHERE is_active;


--
-- Name: ux_er_inflight; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE UNIQUE INDEX ux_er_inflight ON public.estimate_report USING btree (estimate_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: ux_fpv_active; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE UNIQUE INDEX ux_fpv_active ON public.feature_pipeline_version USING btree (is_active) WHERE is_active;


--
-- Name: ux_job_inflight; Type: INDEX; Schema: public; Owner: jaewon
--

CREATE UNIQUE INDEX ux_job_inflight ON public.analysis_job USING btree (accident_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying])::text[]));


--
-- Name: accident_image accident_image_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image
    ADD CONSTRAINT accident_image_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: accident_image_asset accident_image_asset_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_image_asset
    ADD CONSTRAINT accident_image_asset_image_id_fkey FOREIGN KEY (image_id) REFERENCES public.accident_image(image_id) ON DELETE CASCADE;


--
-- Name: accident_review accident_review_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review
    ADD CONSTRAINT accident_review_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: accident_review accident_review_reviewed_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review
    ADD CONSTRAINT accident_review_reviewed_job_id_fkey FOREIGN KEY (reviewed_job_id) REFERENCES public.analysis_job(job_id) ON DELETE SET NULL;


--
-- Name: accident_review accident_review_reviewer_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident_review
    ADD CONSTRAINT accident_review_reviewer_member_id_fkey FOREIGN KEY (reviewer_member_id) REFERENCES public.member(member_id) ON DELETE SET NULL;


--
-- Name: accident accident_vehicle_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.accident
    ADD CONSTRAINT accident_vehicle_id_fkey FOREIGN KEY (vehicle_id) REFERENCES public.vehicle(vehicle_id) ON DELETE RESTRICT;


--
-- Name: analysis_image_result analysis_image_result_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_image_result
    ADD CONSTRAINT analysis_image_result_image_id_fkey FOREIGN KEY (image_id) REFERENCES public.accident_image(image_id) ON DELETE CASCADE;


--
-- Name: analysis_image_result analysis_image_result_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_image_result
    ADD CONSTRAINT analysis_image_result_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.analysis_job(job_id) ON DELETE CASCADE;


--
-- Name: analysis_job analysis_job_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_job
    ADD CONSTRAINT analysis_job_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: analysis_job analysis_job_selected_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_job
    ADD CONSTRAINT analysis_job_selected_part_code_fkey FOREIGN KEY (selected_part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: analysis_stage analysis_stage_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.analysis_stage
    ADD CONSTRAINT analysis_stage_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.analysis_job(job_id) ON DELETE CASCADE;


--
-- Name: audit_log audit_log_actor_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.audit_log
    ADD CONSTRAINT audit_log_actor_member_id_fkey FOREIGN KEY (actor_member_id) REFERENCES public.member(member_id) ON DELETE SET NULL;


--
-- Name: damaged_part damaged_part_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.damaged_part
    ADD CONSTRAINT damaged_part_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.analysis_job(job_id) ON DELETE CASCADE;


--
-- Name: damaged_part damaged_part_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.damaged_part
    ADD CONSTRAINT damaged_part_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: data_validation_error data_validation_error_batch_job_execution_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.data_validation_error
    ADD CONSTRAINT data_validation_error_batch_job_execution_id_fkey FOREIGN KEY (batch_job_execution_id) REFERENCES public.batch_job_execution(batch_job_execution_id) ON DELETE RESTRICT;


--
-- Name: estimate_item estimate_item_damaged_part_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_item
    ADD CONSTRAINT estimate_item_damaged_part_id_fkey FOREIGN KEY (damaged_part_id) REFERENCES public.damaged_part(damaged_part_id) ON DELETE CASCADE;


--
-- Name: estimate_item estimate_item_estimate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_item
    ADD CONSTRAINT estimate_item_estimate_id_fkey FOREIGN KEY (estimate_id) REFERENCES public.estimate(estimate_id) ON DELETE CASCADE;


--
-- Name: estimate estimate_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate
    ADD CONSTRAINT estimate_job_id_fkey FOREIGN KEY (job_id) REFERENCES public.analysis_job(job_id) ON DELETE CASCADE;


--
-- Name: estimate_narrative estimate_narrative_estimate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_narrative
    ADD CONSTRAINT estimate_narrative_estimate_id_fkey FOREIGN KEY (estimate_id) REFERENCES public.estimate(estimate_id) ON DELETE CASCADE;


--
-- Name: estimate_report estimate_report_estimate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_report
    ADD CONSTRAINT estimate_report_estimate_id_fkey FOREIGN KEY (estimate_id) REFERENCES public.estimate(estimate_id) ON DELETE CASCADE;


--
-- Name: estimate_validation estimate_validation_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT estimate_validation_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: estimate_validation estimate_validation_estimate_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT estimate_validation_estimate_id_fkey FOREIGN KEY (estimate_id) REFERENCES public.estimate(estimate_id) ON DELETE SET NULL;


--
-- Name: estimate_validation_item estimate_validation_item_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_item
    ADD CONSTRAINT estimate_validation_item_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE SET NULL;


--
-- Name: estimate_validation_item estimate_validation_item_validation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_item
    ADD CONSTRAINT estimate_validation_item_validation_id_fkey FOREIGN KEY (validation_id) REFERENCES public.estimate_validation(validation_id) ON DELETE CASCADE;


--
-- Name: estimate_validation estimate_validation_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT estimate_validation_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.member(member_id) ON DELETE RESTRICT;


--
-- Name: estimate_validation_question estimate_validation_question_validation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question
    ADD CONSTRAINT estimate_validation_question_validation_id_fkey FOREIGN KEY (validation_id) REFERENCES public.estimate_validation(validation_id) ON DELETE CASCADE;


--
-- Name: estimate_validation_question estimate_validation_question_validation_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_question
    ADD CONSTRAINT estimate_validation_question_validation_item_id_fkey FOREIGN KEY (validation_item_id) REFERENCES public.estimate_validation_item(validation_item_id) ON DELETE CASCADE;


--
-- Name: estimate_validation_report estimate_validation_report_validation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_report
    ADD CONSTRAINT estimate_validation_report_validation_id_fkey FOREIGN KEY (validation_id) REFERENCES public.estimate_validation(validation_id) ON DELETE CASCADE;


--
-- Name: estimate_validation_rule estimate_validation_rule_changed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation_rule
    ADD CONSTRAINT estimate_validation_rule_changed_by_fkey FOREIGN KEY (changed_by) REFERENCES public.member(member_id) ON DELETE SET NULL;


--
-- Name: estimate_validation estimate_validation_rule_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT estimate_validation_rule_version_fkey FOREIGN KEY (rule_version) REFERENCES public.estimate_validation_rule(rule_version) ON DELETE SET NULL;


--
-- Name: estimate_validation fk_ev_rule_version; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.estimate_validation
    ADD CONSTRAINT fk_ev_rule_version FOREIGN KEY (rule_version) REFERENCES public.estimate_validation_rule(rule_version) ON DELETE SET NULL;


--
-- Name: repair_case_damage_feature_part_mapping fk_rcdfpm_primary_candidate; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_mapping
    ADD CONSTRAINT fk_rcdfpm_primary_candidate FOREIGN KEY (primary_candidate_id) REFERENCES public.repair_case_damage_feature_part_candidate(candidate_id) ON DELETE SET NULL;


--
-- Name: part_name_mapping part_name_mapping_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.part_name_mapping
    ADD CONSTRAINT part_name_mapping_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case_damage_feature repair_case_damage_feature_case_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature
    ADD CONSTRAINT repair_case_damage_feature_case_image_id_fkey FOREIGN KEY (case_image_id) REFERENCES public.repair_case_image(case_image_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature_part_candidate repair_case_damage_feature_part_ca_image_part_inference_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate
    ADD CONSTRAINT repair_case_damage_feature_part_ca_image_part_inference_id_fkey FOREIGN KEY (image_part_inference_id) REFERENCES public.repair_case_image_part_inference(image_part_inference_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature_part_candidate repair_case_damage_feature_part_candidat_damage_feature_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate
    ADD CONSTRAINT repair_case_damage_feature_part_candidat_damage_feature_id_fkey FOREIGN KEY (damage_feature_id) REFERENCES public.repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature_part_candidate repair_case_damage_feature_part_candidate_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_candidate
    ADD CONSTRAINT repair_case_damage_feature_part_candidate_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case_damage_feature repair_case_damage_feature_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature
    ADD CONSTRAINT repair_case_damage_feature_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case_damage_feature_part_hint repair_case_damage_feature_part_hint_damage_feature_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_hint
    ADD CONSTRAINT repair_case_damage_feature_part_hint_damage_feature_id_fkey FOREIGN KEY (damage_feature_id) REFERENCES public.repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature_part_hint repair_case_damage_feature_part_hint_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_hint
    ADD CONSTRAINT repair_case_damage_feature_part_hint_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case_damage_feature_part_mapping repair_case_damage_feature_part_ma_image_part_inference_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_mapping
    ADD CONSTRAINT repair_case_damage_feature_part_ma_image_part_inference_id_fkey FOREIGN KEY (image_part_inference_id) REFERENCES public.repair_case_image_part_inference(image_part_inference_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature_part_mapping repair_case_damage_feature_part_mapping_damage_feature_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature_part_mapping
    ADD CONSTRAINT repair_case_damage_feature_part_mapping_damage_feature_id_fkey FOREIGN KEY (damage_feature_id) REFERENCES public.repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE;


--
-- Name: repair_case_damage_feature repair_case_damage_feature_pipeline_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_damage_feature
    ADD CONSTRAINT repair_case_damage_feature_pipeline_version_id_fkey FOREIGN KEY (pipeline_version_id) REFERENCES public.feature_pipeline_version(pipeline_version_id) ON DELETE RESTRICT;


--
-- Name: repair_case_image repair_case_image_case_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image
    ADD CONSTRAINT repair_case_image_case_id_fkey FOREIGN KEY (case_id) REFERENCES public.repair_case(case_id) ON DELETE CASCADE;


--
-- Name: repair_case_image_part_annotation repair_case_image_part_annotation_case_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_annotation
    ADD CONSTRAINT repair_case_image_part_annotation_case_image_id_fkey FOREIGN KEY (case_image_id) REFERENCES public.repair_case_image(case_image_id) ON DELETE CASCADE;


--
-- Name: repair_case_image_part_annotation repair_case_image_part_annotation_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_annotation
    ADD CONSTRAINT repair_case_image_part_annotation_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case_image_part_inference repair_case_image_part_inference_case_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_image_part_inference
    ADD CONSTRAINT repair_case_image_part_inference_case_image_id_fkey FOREIGN KEY (case_image_id) REFERENCES public.repair_case_image(case_image_id) ON DELETE CASCADE;


--
-- Name: repair_case_item repair_case_item_case_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_item
    ADD CONSTRAINT repair_case_item_case_id_fkey FOREIGN KEY (case_id) REFERENCES public.repair_case(case_id) ON DELETE CASCADE;


--
-- Name: repair_case_item repair_case_item_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_item
    ADD CONSTRAINT repair_case_item_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_case repair_case_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case
    ADD CONSTRAINT repair_case_model_id_fkey FOREIGN KEY (model_id) REFERENCES public.vehicle_model(model_id) ON DELETE SET NULL;


--
-- Name: repair_case_roi_embedding repair_case_roi_embedding_case_image_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding
    ADD CONSTRAINT repair_case_roi_embedding_case_image_id_fkey FOREIGN KEY (case_image_id) REFERENCES public.repair_case_image(case_image_id) ON DELETE CASCADE;


--
-- Name: repair_case_roi_embedding repair_case_roi_embedding_damage_feature_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding
    ADD CONSTRAINT repair_case_roi_embedding_damage_feature_id_fkey FOREIGN KEY (damage_feature_id) REFERENCES public.repair_case_damage_feature(damage_feature_id) ON DELETE CASCADE;


--
-- Name: repair_case_roi_embedding repair_case_roi_embedding_model_version_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_case_roi_embedding
    ADD CONSTRAINT repair_case_roi_embedding_model_version_id_fkey FOREIGN KEY (model_version_id) REFERENCES public.embedding_model_version(model_version_id) ON DELETE RESTRICT;


--
-- Name: repair_checklist repair_checklist_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist
    ADD CONSTRAINT repair_checklist_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: repair_checklist_item repair_checklist_item_checklist_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item
    ADD CONSTRAINT repair_checklist_item_checklist_id_fkey FOREIGN KEY (checklist_id) REFERENCES public.repair_checklist(checklist_id) ON DELETE CASCADE;


--
-- Name: repair_checklist_item repair_checklist_item_common_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item
    ADD CONSTRAINT repair_checklist_item_common_code_fkey FOREIGN KEY (common_code) REFERENCES public.repair_checklist_common_item(code) ON DELETE RESTRICT;


--
-- Name: repair_checklist_item repair_checklist_item_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_checklist_item
    ADD CONSTRAINT repair_checklist_item_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_method_rule repair_method_rule_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_method_rule
    ADD CONSTRAINT repair_method_rule_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_question repair_question_accident_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question
    ADD CONSTRAINT repair_question_accident_id_fkey FOREIGN KEY (accident_id) REFERENCES public.accident(accident_id) ON DELETE CASCADE;


--
-- Name: repair_question_item repair_question_item_part_code_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question_item
    ADD CONSTRAINT repair_question_item_part_code_fkey FOREIGN KEY (part_code) REFERENCES public.part_code(part_code) ON DELETE RESTRICT;


--
-- Name: repair_question_item repair_question_item_question_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.repair_question_item
    ADD CONSTRAINT repair_question_item_question_id_fkey FOREIGN KEY (question_id) REFERENCES public.repair_question(question_id) ON DELETE CASCADE;


--
-- Name: terms_agreement terms_agreement_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.terms_agreement
    ADD CONSTRAINT terms_agreement_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.member(member_id) ON DELETE RESTRICT;


--
-- Name: vehicle vehicle_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle
    ADD CONSTRAINT vehicle_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.member(member_id) ON DELETE RESTRICT;


--
-- Name: vehicle vehicle_model_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: jaewon
--

ALTER TABLE ONLY public.vehicle
    ADD CONSTRAINT vehicle_model_id_fkey FOREIGN KEY (model_id) REFERENCES public.vehicle_model(model_id) ON DELETE RESTRICT;


--
-- Name: SCHEMA public; Type: ACL; Schema: -; Owner: pg_database_owner
--

GRANT USAGE ON SCHEMA public TO ai_reader;


--
-- Name: TABLE accident; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.accident TO ai_reader;


--
-- Name: TABLE accident_image; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.accident_image TO ai_reader;


--
-- Name: TABLE accident_image_asset; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.accident_image_asset TO ai_reader;


--
-- Name: TABLE accident_review; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.accident_review TO ai_reader;


--
-- Name: TABLE analysis_image_result; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.analysis_image_result TO ai_reader;


--
-- Name: TABLE analysis_job; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.analysis_job TO ai_reader;


--
-- Name: TABLE analysis_stage; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.analysis_stage TO ai_reader;


--
-- Name: TABLE audit_log; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.audit_log TO ai_reader;


--
-- Name: TABLE batch_job_execution; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.batch_job_execution TO ai_reader;


--
-- Name: TABLE damaged_part; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.damaged_part TO ai_reader;


--
-- Name: TABLE data_validation_error; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.data_validation_error TO ai_reader;


--
-- Name: TABLE embedding_model_version; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.embedding_model_version TO ai_reader;


--
-- Name: TABLE estimate; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate TO ai_reader;


--
-- Name: TABLE estimate_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_item TO ai_reader;


--
-- Name: TABLE estimate_narrative; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_narrative TO ai_reader;


--
-- Name: TABLE estimate_notice; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_notice TO ai_reader;


--
-- Name: TABLE estimate_report; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_report TO ai_reader;


--
-- Name: TABLE estimate_validation; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_validation TO ai_reader;


--
-- Name: TABLE estimate_validation_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_validation_item TO ai_reader;


--
-- Name: TABLE estimate_validation_question; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_validation_question TO ai_reader;


--
-- Name: TABLE estimate_validation_report; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_validation_report TO ai_reader;


--
-- Name: TABLE estimate_validation_rule; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.estimate_validation_rule TO ai_reader;


--
-- Name: TABLE feature_pipeline_version; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.feature_pipeline_version TO ai_reader;


--
-- Name: TABLE member; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.member TO ai_reader;


--
-- Name: TABLE part_code; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.part_code TO ai_reader;


--
-- Name: TABLE part_name_mapping; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.part_name_mapping TO ai_reader;


--
-- Name: TABLE repair_case; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case TO ai_reader;


--
-- Name: TABLE repair_case_damage_feature; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_damage_feature TO ai_reader;


--
-- Name: TABLE repair_case_damage_feature_part_candidate; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_damage_feature_part_candidate TO ai_reader;


--
-- Name: TABLE repair_case_damage_feature_part_hint; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_damage_feature_part_hint TO ai_reader;


--
-- Name: TABLE repair_case_damage_feature_part_mapping; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_damage_feature_part_mapping TO ai_reader;


--
-- Name: TABLE repair_case_image; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_image TO ai_reader;


--
-- Name: TABLE repair_case_image_part_annotation; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_image_part_annotation TO ai_reader;


--
-- Name: TABLE repair_case_image_part_inference; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_image_part_inference TO ai_reader;


--
-- Name: TABLE repair_case_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_item TO ai_reader;


--
-- Name: TABLE repair_case_roi_embedding; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_case_roi_embedding TO ai_reader;


--
-- Name: TABLE repair_checklist; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_checklist TO ai_reader;


--
-- Name: TABLE repair_checklist_common_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_checklist_common_item TO ai_reader;


--
-- Name: TABLE repair_checklist_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_checklist_item TO ai_reader;


--
-- Name: TABLE repair_code; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_code TO ai_reader;


--
-- Name: TABLE repair_cost_stat; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_cost_stat TO ai_reader;


--
-- Name: TABLE repair_method_rule; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_method_rule TO ai_reader;


--
-- Name: TABLE repair_question; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_question TO ai_reader;


--
-- Name: TABLE repair_question_item; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.repair_question_item TO ai_reader;


--
-- Name: TABLE terms_agreement; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.terms_agreement TO ai_reader;


--
-- Name: TABLE vehicle; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.vehicle TO ai_reader;


--
-- Name: TABLE vehicle_model; Type: ACL; Schema: public; Owner: jaewon
--

GRANT SELECT ON TABLE public.vehicle_model TO ai_reader;


--
-- Name: DEFAULT PRIVILEGES FOR TABLES; Type: DEFAULT ACL; Schema: public; Owner: jaewon
--

ALTER DEFAULT PRIVILEGES FOR ROLE jaewon IN SCHEMA public GRANT SELECT ON TABLES TO ai_reader;


--
-- PostgreSQL database dump complete
--

\unrestrict HhNeb2ii2l8cAHzRxaeqEUU5jAihxgTJfLV0GUZc1LvFeV15S1D9QDXU52IKyaA

