-- Phase 4 follow-up: bounds CHECK constraints on quizzes, in the same spirit as V18's own
-- selected_index/mode/status CHECKs — V18 has already been applied (and checksummed) against
-- real databases in this environment, so these are added via ALTER TABLE in a new migration
-- rather than edited into V18 in place (see docs/DECISIONS.md's V12 checksum-drift entry for
-- exactly what goes wrong when a migration's on-disk content changes after it's been applied).
ALTER TABLE quizzes ADD CONSTRAINT quizzes_question_count_positive CHECK (question_count > 0);
ALTER TABLE quizzes ADD CONSTRAINT quizzes_time_limit_seconds_positive
    CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0);
ALTER TABLE quizzes ADD CONSTRAINT quizzes_negative_marking_fraction_range
    CHECK (negative_marking_fraction BETWEEN 0 AND 1);
