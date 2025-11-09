package star.tracto.rqs.mutation.data.records;

import com.fasterxml.jackson.annotation.JsonProperty;

public record InferenceRecord(
        @JsonProperty("test_id") String testId,
        @JsonProperty("test_class_path") String testClassPath,
        @JsonProperty("class_name") String className,
        @JsonProperty("test_name") String testName,
        @JsonProperty("signature") String signature,
        @JsonProperty("tp_body") String tpBody,
        @JsonProperty("tgt") String tgt,
        @JsonProperty("generated") String generated,
        @JsonProperty("status") String status,
        @JsonProperty("time") int time,
        @JsonProperty("message") String message
) {}