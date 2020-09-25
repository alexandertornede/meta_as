# meta_as

## Start experiments for meta-level N
1. Copy results for meta-level N-1 from the "output" folder on the server into a new subdirectory in output named "level_{N-1}".
2. Change "data_folder" in "conf/experiment_configuration" to "data/level_N/"
2. Change "table" in "conf/experiment_configuration" to "server_results_meta_level_N"
3. Change level in "meta_aslib_preparation" to level N
4. Run "meta_aslib_preparation" for meta-level N and copy the data from "data/level_N" to the server into the same directory
5. Check tables in SQL server