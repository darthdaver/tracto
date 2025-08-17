import os
import sys
import json

if __name__ == "__main__":
    tokens_stats_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "output", "statistics-tokens-dataset-training"))

    stats_percentage = {
        "total_tokens": 0,
        "total_missed": 0,
        "total_matched": 0,
        "percentage_missed": 0.0,
        "percentage_matched": 0.0,
        "projects": {}
    }

    for root, _, files in os.walk(tokens_stats_dir):
        for file in files:
            if file.endswith(".json"):
                project_tokens = 0
                project_missed = 0
                project_matched = 0
                project_id = file.replace("oracles-datapoints-", "").split("-")[0]
                file_path = os.path.join(root, file)
                with open(file_path, 'r') as f:
                    stats = json.load(f)
                for tc_path, tc_stats in stats.items():
                    for tp_id, tp_stats in tc_stats.items():
                        project_missed += len(tp_stats["targetMissing"])
                        project_matched += len(tp_stats["targetFound"])
                        project_tokens += len(tp_stats["targetMissing"]) + len(tp_stats["targetFound"])
                stats_percentage["total_tokens"] += project_tokens
                stats_percentage["total_missed"] += project_missed
                stats_percentage["total_matched"] += project_matched

                if not project_id in stats_percentage["projects"]:
                    stats_percentage["projects"][project_id] = {
                        "tokens": project_tokens,
                        "missed": project_missed,
                        "matched": project_matched,
                        "percentage_missed": (project_missed / project_tokens) * 100 if project_tokens > 0 else 0.0,
                        "percentage_matched": (project_matched / project_tokens) * 100 if project_tokens > 0 else 0.0
                    }
                else:
                    stats_percentage["projects"][project_id]["tokens"] += project_tokens
                    stats_percentage["projects"][project_id]["missed"] += project_missed
                    stats_percentage["projects"][project_id]["matched"] += project_matched
                    stats_percentage["projects"][project_id]["percentage_missed"] = (stats_percentage["projects"][project_id]["missed"] / stats_percentage["projects"][project_id]["tokens"]) * 100 if stats_percentage["projects"][project_id]["tokens"] > 0 else 0.0
                    stats_percentage["projects"][project_id]["percentage_matched"] = (stats_percentage["projects"][project_id]["matched"] / stats_percentage["projects"][project_id]["tokens"]) * 100 if stats_percentage["projects"][project_id]["tokens"] > 0 else 0.0
    stats_percentage["percentage_missed"] = (stats_percentage["total_missed"] / stats_percentage["total_tokens"]) * 100 if stats_percentage["total_tokens"] > 0 else 0.0
    stats_percentage["percentage_matched"] = (stats_percentage["total_matched"] / stats_percentage["total_tokens"]) * 100 if stats_percentage["total_tokens"] > 0 else 0.0
    with open(os.path.join(tokens_stats_dir, "percentage_tokens_retrieved.json"), 'w') as f:
        json.dump(stats_percentage, f, indent=4)

