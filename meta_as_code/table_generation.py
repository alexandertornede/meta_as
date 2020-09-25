import configparser
import pandas as pd

RESULT_TABLE_LEVEL_0 = "server_results_meta_level_0"
RESULT_TABLE_LEVEL_1 = "server_results_meta_level_1_new"


def load_configuration():
    config = configparser.ConfigParser()
    config.read_file(open('conf/experiment_configuration.cfg'))
    return config

def generate_sbs_vbs_change_table():
    dataframe = get_dataframe_for_sql_query("SELECT scenario_name, AVG(oracle_result_level_0) as VBS_0, AVG(oracle_result_level_1) as VBS_1, AVG(oracle_level_1_div_oracle_level_0) as 'VBS_1/VBS_0', AVG(sbs_result_level_0) as SBS_0, AVG(sbs_result_level_1) as SBS_1, AVG(sbs_level_0_div_sbs_level_1) as 'SBS_0/SBS_1', AVG(sbs_result_level_0_div_oracle_result_level_0) as 'SBS/VBS 0', AVG(sbs_result_level_1_div_oracle_result_level_1) as 'SBS/VBS 1' FROM `complete_sbs_vbs_and_gap_overview` GROUP BY scenario_name ORDER BY scenario_name")
    print(dataframe.to_latex(index=False, float_format="%.3f"))

def get_dataframe_for_sql_query(sql_query: str ):
    db_credentials = get_database_credential_string()
    return pd.read_sql(sql_query, con=db_credentials)

def get_database_credential_string():
    config = load_configuration()
    db_config_section = config['DATABASE']
    db_host = db_config_section['host']
    db_username = db_config_section['username']
    db_password = db_config_section['password']
    db_database = db_config_section['database']
    return "mysql://" + db_username + ":" + db_password + "@" + db_host + "/" + db_database

generate_sbs_vbs_change_table()