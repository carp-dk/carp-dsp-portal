-- Every stored sequence for one study, as core.io.decode-carp-data-streams reads it.
-- ORDER BY is required: sequences of one stream have to arrive in order.
-- :from and :to select by arrival time; unset, every sequence is read.
-- Only the streams the live comparison reads: steps, surveys and activity.
SELECT i.study_deployment_id,
       i.device_role_name,
       i.name_space || '.' || i.name AS data_type,
       s.first_sequence_id,
       s.snapshot
FROM data_stream_sequence s
         JOIN data_stream_ids i
              ON i.id = s.data_stream_id
         JOIN recruitment_participant_groups g
              ON g.group_id = i.study_deployment_id
                  AND g.is_deployed
WHERE g.study_id = :studyId
  AND i.name IN ('stepcount', 'stepevent', 'activity', 'survey')
  AND (CAST(:from AS timestamp) IS NULL OR s.created_at >= :from)
  AND (CAST(:to AS timestamp) IS NULL OR s.created_at < :to)
  AND NOT EXISTS (
      -- A re-upload covered by another sequence of the same stream. Of two
      -- identical ranges, the first stored is kept.
      SELECT 1
      FROM data_stream_sequence o
      WHERE o.data_stream_id = s.data_stream_id
        AND o.id <> s.id
        AND o.first_sequence_id <= s.first_sequence_id
        AND o.last_sequence_id >= s.last_sequence_id
        AND (o.first_sequence_id < s.first_sequence_id
            OR o.last_sequence_id > s.last_sequence_id
            OR o.id < s.id))
ORDER BY i.id, s.first_sequence_id;
