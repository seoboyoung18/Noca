-- A307 검색용 part_code 기준 데이터: YOLO 핵심 32종 + 견적 전용 확장 코드 24종.
BEGIN;

INSERT INTO part_code (part_code, name_ko, layout_zone, display_order, is_active)
VALUES
    ('FRONT_BUMPER','앞 범퍼','FRONT',1,TRUE), ('REAR_BUMPER','뒤 범퍼','REAR',2,TRUE),
    ('BONNET','보닛','FRONT',3,TRUE), ('TRUNK_LID','트렁크 리드','REAR',4,TRUE),
    ('ROOF','루프','TOP',5,TRUE), ('WINDSHIELD','앞 유리','FRONT',6,TRUE),
    ('REAR_WINDSHIELD','뒤 유리','REAR',7,TRUE), ('UNDERCARRIAGE','차량 하부','UNDER',8,TRUE),
    ('A_PILLAR_L','A 필러(좌)','SIDE_L',9,TRUE), ('A_PILLAR_R','A 필러(우)','SIDE_R',10,TRUE),
    ('C_PILLAR_L','C 필러(좌)','SIDE_L',11,TRUE), ('C_PILLAR_R','C 필러(우)','SIDE_R',12,TRUE),
    ('FRONT_DOOR_L','앞 도어(좌)','SIDE_L',13,TRUE), ('FRONT_DOOR_R','앞 도어(우)','SIDE_R',14,TRUE),
    ('REAR_DOOR_L','뒤 도어(좌)','SIDE_L',15,TRUE), ('REAR_DOOR_R','뒤 도어(우)','SIDE_R',16,TRUE),
    ('FRONT_FENDER_L','앞 펜더(좌)','SIDE_L',17,TRUE), ('FRONT_FENDER_R','앞 펜더(우)','SIDE_R',18,TRUE),
    ('REAR_FENDER_L','뒤 펜더(좌)','SIDE_L',19,TRUE), ('REAR_FENDER_R','뒤 펜더(우)','SIDE_R',20,TRUE),
    ('FRONT_WHEEL_L','앞 휠(좌)','SIDE_L',21,TRUE), ('FRONT_WHEEL_R','앞 휠(우)','SIDE_R',22,TRUE),
    ('REAR_WHEEL_L','뒤 휠(좌)','SIDE_L',23,TRUE), ('REAR_WHEEL_R','뒤 휠(우)','SIDE_R',24,TRUE),
    ('HEAD_LIGHT_L','헤드램프(좌)','SIDE_L',25,TRUE), ('HEAD_LIGHT_R','헤드램프(우)','SIDE_R',26,TRUE),
    ('REAR_LAMP_L','리어램프(좌)','SIDE_L',27,TRUE), ('REAR_LAMP_R','리어램프(우)','SIDE_R',28,TRUE),
    ('ROCKER_PANEL_L','로커 패널(좌)','SIDE_L',29,TRUE), ('ROCKER_PANEL_R','로커 패널(우)','SIDE_R',30,TRUE),
    ('SIDE_MIRROR_L','사이드미러(좌)','SIDE_L',31,TRUE), ('SIDE_MIRROR_R','사이드미러(우)','SIDE_R',32,TRUE),
    ('RADIATOR_GRILLE','라디에이터 그릴','FRONT',101,TRUE), ('QUARTER_GLASS_L','뒤 쿼터글라스(좌)','SIDE_L',102,TRUE),
    ('QUARTER_GLASS_R','뒤 쿼터글라스(우)','SIDE_R',103,TRUE), ('REAR_BUMPER_UNDERCOVER','뒤 범퍼 언더커버','REAR',104,TRUE),
    ('PARKING_SENSOR_FRONT','앞 주차 감지센서','FRONT',105,TRUE), ('PARKING_SENSOR_REAR','뒤 주차 감지센서','REAR',106,TRUE),
    ('FOG_LAMP_L','포그램프(좌)','SIDE_L',107,TRUE), ('FOG_LAMP_R','포그램프(우)','SIDE_R',108,TRUE),
    ('SIDE_STEP_L','사이드스텝(좌)','SIDE_L',109,TRUE), ('SIDE_STEP_R','사이드스텝(우)','SIDE_R',110,TRUE),
    ('DOOR_BELT_MOLDING_FRONT_L','앞 도어밸트(눈썹)몰딩(좌)','SIDE_L',111,TRUE),
    ('DOOR_BELT_MOLDING_FRONT_R','앞 도어밸트(눈썹)몰딩(우)','SIDE_R',112,TRUE),
    ('DOOR_BELT_MOLDING_REAR_L','뒤 도어밸트(눈썹)몰딩(좌)','SIDE_L',113,TRUE),
    ('DOOR_BELT_MOLDING_REAR_R','뒤 도어밸트(눈썹)몰딩(우)','SIDE_R',114,TRUE),
    ('BACK_PANEL','백패널(리어패널)','REAR',115,TRUE), ('COWL_GRILLE','카울그릴','FRONT',116,TRUE),
    ('WASHER_TANK','와셔탱크','FRONT',117,TRUE), ('REAR_BUMPER_LAMP_L','뒤 범퍼 램프(좌)','SIDE_L',118,TRUE),
    ('REAR_BUMPER_LAMP_R','뒤 범퍼 램프(우)','SIDE_R',119,TRUE), ('EMBLEM','엠블램','FRONT',120,TRUE),
    ('FRONT_PANEL','프런트 패널','FRONT',121,TRUE), ('REAR_CAMERA','후방카메라','REAR',122,TRUE),
    ('SLIDING_DOOR_L','슬라이딩 도어(좌)','SIDE_L',123,TRUE), ('SLIDING_DOOR_R','슬라이딩 도어(우)','SIDE_R',124,TRUE)
ON CONFLICT (part_code) DO UPDATE
SET name_ko = EXCLUDED.name_ko,
    layout_zone = EXCLUDED.layout_zone,
    display_order = EXCLUDED.display_order,
    is_active = EXCLUDED.is_active;

COMMIT;
