import os
import sys
import pandas as pd
# from transformers import AutoTokenizer
sys.path.append(os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "neural-module"))
from src.utils.Logger import Logger
# Setup the logger
logger = Logger("main", "RQ1 - stats")

def count_model_query_tokens(query, tokenizer_name):
    try:
        tokenizer = AutoTokenizer.from_pretrained(tokenizer_name)
    except Exception as e:
        default_tokenizer = "codellama/CodeLlama-7b-hf"
        tokenizer = AutoTokenizer.from_pretrained(default_tokenizer)
    tokens = len(tokenizer.encode(query, add_special_tokens=False))
    return tokens

def count_moderators_query_tokens(query, moderators_tokenizer_name_list):
    tokens = []
    for tokenizer_name in moderators_tokenizer_name_list:
        try:
            tokenizer = AutoTokenizer.from_pretrained(tokenizer_name)
        except Exception as e:
            default_tokenizer = "codellama/CodeLlama-7b-hf"
            tokenizer = AutoTokenizer.from_pretrained(default_tokenizer)
        tokens.append(len(tokenizer.encode(query, add_special_tokens=False)))
    return max(tokens)


if __name__ == "__main__":
    # Paths
    output_analysis_dir_path = os.path.join(os.path.dirname(__file__), '..', '..', '..', 'output', 'analysis')
    output_stats_dir_path = os.path.join(os.path.dirname(__file__), '..', '..', '..', 'output', 'stats')
    model_list_file_path = os.path.join(os.path.dirname(__file__), '..', '..', 'resources', 'models-list.csv')
    moderator_list_file_path = os.path.join(os.path.dirname(__file__), '..', '..', 'resources', 'moderators-list.csv')
    # Data
    query_template_list = [1,2,3,4]
    model_list = pd.read_csv(model_list_file_path, header=None)
    moderator_list = pd.read_csv(moderator_list_file_path, header=None)
    stats_columns=[
        "model_id", # The model identifier
        "query_id", # The query identifier
        "q_pred_mean", # The mean value of the prompt length (prediction)
        "q_pred_std", # The standard deviation of the prompt length (prediction)
        "q_pred_min", # The minimum value of the prompt length (prediction)
        "q_pred_25%", # The first quartile of the prompt length (prediction)
        "q_pred_50%", # The median value of the prompt length (prediction)
        "q_pred_75%", # The third quartile of the prompt length (prediction)
        "q_pred_max", # The maximum value of the prompt length (prediction)
        "q_pred_over_limit", # The number of predictions exceeding the token limit
        "q_mod_mean",  # The mean value of the prompt length (moderator)
        "q_mod_std",  # The standard deviation of the prompt length (moderator)
        "q_mod_min",  # The minimum value of the prompt length (moderator)
        "q_mod_25%",  # The first quartile of the prompt length (moderator)
        "q_mod_50%",  # The median value of the prompt length (moderator)
        "q_mod_75%",  # The third quartile of the prompt length (moderator)
        "q_mod_max",  # The maximum value of the prompt length (moderator)
        "match", # The number of exact matches (target == predicted)
        "equivalent", # The number of equivalent target and predicted assertions
        "percentage_equivalent", # The percentage of equivalent target and predicted assertions
        "mean_equivalent_threshold", # The mean equivalent threshold
        "std_equivalent_threshold", # The standard deviation equivalent threshold
        "correct_match_equivalent", # The number of exact matches judged as equivalent also by majority of the moderators
        "percentage_correct_match_equivalent", # The percentage of exact matches judged as equivalent also by majority of the moderators
        "not_equivalent", # The number of assertions judged as not equivalent by the majority of the moderators
        "percentage_not_equivalent", # The percentage of assertions judged as not equivalent by the majority of the moderators
        "incorrect_not_equivalent", # The number of assertions judged as not equivalent by the majority of the moderators but that are actually matches
        "percentage_incorrect_not_equivalent", # The percentage of assertions judged as not equivalent by the majority of the moderators but that are actually matches
        "total", # The total number of assertions
        *[ f"correct_match_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"percentage_correct_match_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"incorrect_match_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows()],
        *[ f"percentage_incorrect_match_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows()],
        *[ f"equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"percentage_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"not_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"percentage_not_equivalent_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"unknown_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"percentage_unknown_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"error_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ],
        *[ f"percentage_error_{moderator.iloc[0]}" for idx, moderator in moderator_list.iterrows() ]
    ]
    stats_collector = []
    for idx, model in model_list.iterrows():
        model_id = model.iloc[0]
        tokenizer_name = model.iloc[1]
        model_ctx = int(model.iloc[2])
        for query_id in query_template_list:
            df_model = None
            model_analysis_dir = f"{model_id}-{query_id}"
            if model_analysis_dir not in os.listdir(output_analysis_dir_path):
                logger.log(f"Model {model_id} - template {query_id} not found in analysis folder. Discarded.")
                continue
            for filename in os.listdir(os.path.join(output_analysis_dir_path, model_analysis_dir)):
                if filename.endswith(".json"):
                    if df_model is None:
                        df_model = pd.read_json(os.path.join(output_analysis_dir_path, model_analysis_dir, filename))
                    else:
                        df_model = pd.concat([df_model, pd.read_json(os.path.join(output_analysis_dir_path, model_analysis_dir, filename))], ignore_index=True)
            if df_model is None:
                logger.log(f"Model {model_id} - template {query_id} files not found in analysis folder. Discarded.")
                continue
            df_model['equivalence_ratio'] = (df_model['equivalence_counter'] / len(moderator_list)).round(2)
            df_model['equivalent_verdict'] = df_model['equivalence_counter'] >= (len(moderator_list) / 2)
            prompt_prediction_lengths = df_model["prompt-prediction"].str.len() # df_model["prompt-prediction"].apply(lambda r: count_model_query_tokens(r, tokenizer_name))
            prompt_moderator_lengths = df_model["prompt-moderator"].str.len() # df_model["prompt-moderator"].apply(lambda r: count_moderators_query_tokens(r, moderator_list.iloc[:,1].tolist()))
            model_query_stats = {
                "model_id": model_id,
                "query_id": query_id,
                "q_pred_mean": round(prompt_prediction_lengths.mean(),2),
                "q_pred_std": round(prompt_prediction_lengths.std(),2),
                "q_pred_min": prompt_prediction_lengths.min(),
                "q_pred_25%": round(prompt_prediction_lengths.quantile(0.25),2),
                "q_pred_50%": round(prompt_prediction_lengths.quantile(0.50),2),
                "q_pred_75%": round(prompt_prediction_lengths.quantile(0.75),2),
                "q_pred_max": prompt_prediction_lengths.max(),
                "q_pred_over_limit": prompt_prediction_lengths[prompt_prediction_lengths > model_ctx].shape[0],
                "q_mod_mean": round(prompt_moderator_lengths.mean(),2),
                "q_mod_std": round(prompt_moderator_lengths.std(),2),
                "q_mod_min": prompt_moderator_lengths.min(),
                "q_mod_25%": round(prompt_moderator_lengths.quantile(0.25),2),
                "q_mod_50%": round(prompt_moderator_lengths.quantile(0.50),2),
                "q_mod_75%": round(prompt_moderator_lengths.quantile(0.75),2),
                "q_mod_max": prompt_moderator_lengths.max(),
                "match": df_model[df_model["match"] == True].shape[0],
                "equivalent": df_model[df_model["equivalent_verdict"] == True].shape[0],
                "percentage_equivalent": round(df_model[df_model["equivalent_verdict"] == True].shape[0] / df_model.shape[0] * 100, 2),
                "mean_equivalent_threshold": round(df_model['equivalence_counter'].mean(),2),
                "std_equivalent_threshold": round(df_model['equivalence_counter'].std(),2),
                "correct_match_equivalent": df_model[(df_model["match"] == True) & (df_model["equivalent_verdict"] == True)].shape[0],
                "percentage_correct_match_equivalent": round(df_model[(df_model["match"] == True) & (df_model["equivalent_verdict"] == True)].shape[0] / df_model[df_model["match"] == True].shape[0] * 100, 2),
                "not_equivalent": df_model[(df_model["equivalent_verdict"] == False)].shape[0],
                "percentage_not_equivalent": round(df_model[(df_model["equivalent_verdict"] == False)].shape[0] / df_model.shape[0] * 100, 2),
                "incorrect_not_equivalent": df_model[(df_model["match"] == True) & (df_model["equivalent_verdict"] == False)].shape[0],
                "percentage_incorrect_not_equivalent": round(df_model[(df_model["match"] == True) & (df_model["equivalent_verdict"] == False)].shape[0] / df_model[df_model["match"] == True].shape[0] * 100, 2),
                "total": df_model.shape[0],
                **{ f"correct_match_equivalent_{m.iloc[0]}": df_model[(df_model["match"] == True) & (df_model[m.iloc[0]] == "EQUIVALENT")].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_correct_match_equivalent_{m.iloc[0]}": round(df_model[(df_model["match"] == True) & (df_model[m.iloc[0]] == "EQUIVALENT")].shape[0] / df_model[df_model["match"] == True].shape[0] * 100, 2) for idx, m in moderator_list.iterrows() },
                **{ f"incorrect_match_equivalent_{m.iloc[0]}": df_model[(df_model["match"] == True) & (df_model[m.iloc[0]] == "NOT EQUIVALENT")].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_incorrect_match_equivalent_{m.iloc[0]}": round(df_model[(df_model["match"] == True) & (df_model[m.iloc[0]] == "NOT EQUIVALENT")].shape[0] / df_model[df_model["match"] == True].shape[0] * 100, 2) for idx, m in moderator_list.iterrows() },
                **{ f"equivalent_{m.iloc[0]}": df_model[df_model[m.iloc[0]] == "EQUIVALENT"].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_equivalent_{m.iloc[0]}": round(df_model[df_model[m.iloc[0]] == "EQUIVALENT"].shape[0] / df_model.shape[0] * 100, 2) for idx, m in moderator_list.iterrows() },
                **{ f"not_equivalent_{m.iloc[0]}": df_model[df_model[m.iloc[0]] == "NOT EQUIVALENT"].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_not_equivalent_{m.iloc[0]}": round(df_model[df_model[m.iloc[0]] == "NOT EQUIVALENT"].shape[0] / df_model.shape[0] * 100, 2) for idx, m in moderator_list.iterrows() },
                **{ f"unknown_{m.iloc[0]}": df_model[df_model[m.iloc[0]] == "UNKNOWN"].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_unknown_{m.iloc[0]}": round(df_model[df_model[m.iloc[0]] == "UNKNOWN"].shape[0] / df_model.shape[0] * 100, 2) for idx, m in moderator_list.iterrows() },
                **{ f"error_{m.iloc[0]}": df_model[df_model[m.iloc[0]] == "ERROR"].shape[0] for idx, m in moderator_list.iterrows() },
                **{ f"percentage_error_{m.iloc[0]}": round(df_model[df_model[m.iloc[0]] == "ERROR"].shape[0] / df_model.shape[0] * 100, 2) for idx, m in moderator_list.iterrows() }
            }
            prompt_moderator_lengths = df_model["prompt-moderator"].str.len()
            # Compute the statistics
            stats_collector.append(model_query_stats)
    stats = pd.DataFrame(stats_collector, columns=stats_columns)
    if not os.path.exists(output_stats_dir_path):
        os.makedirs(output_stats_dir_path)
    # Save the statistics
    stats.to_csv(os.path.join(output_stats_dir_path, "stats.csv"), index=False)
    stats.to_json(os.path.join(output_stats_dir_path, "stats.json"), orient="records", indent=4)