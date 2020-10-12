import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.aeonbits.owner.ConfigFactory;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.api4.java.datastructure.kvstore.IKVStore;

import ai.libs.jaicore.basic.StatisticsUtil;
import ai.libs.jaicore.basic.ValueUtil;
import ai.libs.jaicore.basic.kvstore.KVStore;
import ai.libs.jaicore.basic.kvstore.KVStoreCollection;
import ai.libs.jaicore.basic.kvstore.KVStoreCollectionOneLayerPartition;
import ai.libs.jaicore.basic.kvstore.KVStoreCollectionTwoLayerPartition;
import ai.libs.jaicore.basic.kvstore.KVStoreSequentialComparator;
import ai.libs.jaicore.basic.kvstore.KVStoreStatisticsUtil;
import ai.libs.jaicore.basic.kvstore.KVStoreUtil;
import ai.libs.jaicore.db.IDatabaseConfig;
import ai.libs.jaicore.db.sql.SQLAdapter;

public class TableGenerator {

	private static final SQLAdapter adapter = new SQLAdapter((IDatabaseConfig) ConfigFactory.create(IDatabaseConfig.class).loadPropertiesFromFile(new File("database.properties")));

	private static final String TBL_1 = "normalized_par10_level_0";
	private static final String TBL_2 = "normalized_par10_level_1";
	private static final String TBL_3 = "normalized_by_level0_par10_level_1";

	private static final int TRIM_ELEMENTS = 2;

	public static void main(final String[] args) throws SQLException {
		Map<String, String> replacement = new HashMap<>();
		replacement.put("Expectation_algorithm_survival_forest", "00-R2SExp");
		replacement.put("PAR10_algorithm_survival_forest", "10-R2SPAR10");
		replacement.put("isac", "20-ISAC");
		replacement.put("multiclass_algorithm_selector", "30-MLC");
		replacement.put("per_algorithm_RandomForestRegressor_regressor", "35-PAReg");
		replacement.put("satzilla-11", "40-Satzilla");
		replacement.put("sunny", "50-Sunny");

		KVStoreCollection col1 = KVStoreUtil.readFromMySQLQuery(adapter,
				"SELECT * FROM ((SELECT * FROM normalized_by_level_0_par10_level_1 UNION SELECT * FROM normalized_par10_level_0) as union_table) WHERE approach NOT IN ('sbs', 'oracle', 'sbs_with_feature_costs','l1_sbs', 'l1_oracle', 'l1_sbs_with_feature_costs') AND scenario_name != 'CSP-MZN-2013 '",
				new HashMap<>());
		// KVStoreCollection col1 = KVStoreUtil.readFromMySQLTable(adapter, TBL_1, new HashMap<>());
		col1.removeAny(new String[] { "sbs", "oracle" }, true);

		col1 = col1.group("scenario_name", "approach");
		col1.stream().forEach(x -> {
			String approach = x.getAsString("approach");

			if (approach.startsWith("l1")) {
				x.put("approach", "l1-" + replacement.computeIfAbsent(approach.substring(3), t -> t));
			} else {
				x.put("approach", replacement.computeIfAbsent(approach, t -> t));
			}
		});

		for (IKVStore store : col1) {
			List<Double> list = store.getAsDoubleList("n_par10");
			Collections.sort(list);
			for (int i = 0; i < TRIM_ELEMENTS; i++) {
				list.remove(0);
				list.remove(list.size() - 1);
			}
			store.put("tm_n_par10", ValueUtil.valueToString(StatisticsUtil.mean(list), 2));
		}

		KVStoreStatisticsUtil.rank(col1, "scenario_name", "approach", "tm_n_par10", "rank");
		Map<String, DescriptiveStatistics> avgRankCol1 = KVStoreStatisticsUtil.averageRank(col1, "approach", "rank");

		// Win Tie Loss Statistics
		KVStoreCollection baseApproaches = new KVStoreCollection();
		col1.stream().filter(x -> !x.getAsString("approach").startsWith("l1")).forEach(baseApproaches::add);

		KVStoreCollectionTwoLayerPartition scenarioWiseBaseApproaches = new KVStoreCollectionTwoLayerPartition("approach", "scenario_name", baseApproaches);

		KVStoreCollection metaApproaches = new KVStoreCollection();
		col1.stream().filter(x -> x.getAsString("approach").startsWith("l1")).forEach(metaApproaches::add);

		KVStoreCollectionTwoLayerPartition partition = new KVStoreCollectionTwoLayerPartition("approach", "scenario_name", metaApproaches);

		KVStoreCollection countData = new KVStoreCollection();
		for (Entry<String, Map<String, KVStoreCollection>> metaPartEntry : partition) {
			for (Entry<String, Map<String, KVStoreCollection>> basePartEntry : scenarioWiseBaseApproaches) {
				int win = 0;
				int tie = 0;
				int loss = 0;

				for (String key : metaPartEntry.getValue().keySet()) {
					IKVStore metaStore = metaPartEntry.getValue().get(key).get(0);
					IKVStore baseStore = basePartEntry.getValue().get(key).get(0);
					switch (metaStore.getAsDouble("tm_n_par10").compareTo(baseStore.getAsDouble("tm_n_par10"))) {
					case -1:
						win++;
						break;
					case 0:
						tie++;
						break;
					case 1:
						loss++;
						break;
					}
				}

				IKVStore store = new KVStore();
				store.put("meta_approach", metaPartEntry.getKey());
				store.put("base_approach", basePartEntry.getKey());

				store.put("win", win);
				store.put("tie", tie);
				store.put("loss", loss);

				store.put("entry", win + "/" + tie + "/" + loss);
				countData.add(store);
			}
		}

		System.out.println("#####");
		countData.sort(new KVStoreSequentialComparator("scenario_name", "approach"));
		System.out.println(KVStoreUtil.kvStoreCollectionToLaTeXTable(countData, "base_approach", "meta_approach", "entry"));

		KVStoreCollectionOneLayerPartition meanWTLStats = new KVStoreCollectionOneLayerPartition("approach", countData);
		for (Entry<String, KVStoreCollection> entry : meanWTLStats) {
			List<Double> win = new ArrayList<>();
			List<Double> tie = new ArrayList<>();
			List<Double> loss = new ArrayList<>();

			for (IKVStore store : entry.getValue()) {
				win.add(store.getAsDouble("win"));
				tie.add(store.getAsDouble("tie"));
				loss.add(store.getAsDouble("loss"));
			}

			System.out.println(entry.getKey() + " " + ValueUtil.valueToString(median(win), 2) + " " + ValueUtil.valueToString(median(loss), 2));
		}

		System.out.println("#####");

		// Result Table (cont.)
		KVStoreStatisticsUtil.best(col1, "scenario_name", "approach", "tm_n_par10", "best");
		col1.stream().forEach(x -> {
			if (x.getAsBoolean("best")) {
				x.put("tm_n_par10", "\\textbf{" + x.getAsString("tm_n_par10") + "}");
			}

			if (x.getAsString("approach").startsWith("l1")) {
				x.put("tm_n_par10", x.getAsString("tm_n_par10") + " (" + x.getAsString("statistics") + ")");
			}
		});

		col1.sort(new KVStoreSequentialComparator("scenario_name", "approach"));

		String latexTable = KVStoreUtil.kvStoreCollectionToLaTeXTable(col1, "scenario_name", "approach", "tm_n_par10");
		System.out.println(latexTable);

		KVStoreCollection col2 = KVStoreUtil.readFromMySQLTable(adapter, TBL_2, new HashMap<>());
		col2 = KVStoreUtil.readFromMySQLQuery(adapter,
				"SELECT oracle_and_sbs_table.scenario_name, oracle_and_sbs_table.fold, server_results_meta_level_1.approach, oracle_and_sbs_table.metric, server_results_meta_level_1.result, ((server_results_meta_level_1.result - oracle_and_sbs_table.oracle_result)/(oracle_and_sbs_table.sbs_result -oracle_and_sbs_table.oracle_result)) as n_par10,oracle_and_sbs_table.oracle_result, oracle_and_sbs_table.sbs_result FROM (SELECT oracle_table.scenario_name, oracle_table.fold, oracle_table.metric, oracle_result, sbs_result FROM (SELECT scenario_name, fold, approach, metric, result as oracle_result FROM `server_results_meta_level_1` WHERE approach='oracle') as oracle_table JOIN (SELECT scenario_name, fold, approach, metric, result as sbs_result FROM `server_results_meta_level_1` WHERE approach='sbs_with_feature_costs') as sbs_table ON oracle_table.scenario_name = sbs_table.scenario_name AND oracle_table.fold=sbs_table.fold AND oracle_table.metric = sbs_table.metric) as oracle_and_sbs_table JOIN server_results_meta_level_1 ON oracle_and_sbs_table.scenario_name = server_results_meta_level_1.scenario_name AND oracle_and_sbs_table.fold = server_results_meta_level_1.fold AND oracle_and_sbs_table.metric = server_results_meta_level_1.metric WHERE oracle_and_sbs_table.metric='par10'",
				new HashMap<>());
		col2.removeAny(new String[] { "approach=sbs", "approach=oracle" }, true);
		col2 = col2.group("scenario_name", "approach");
		col2.stream().forEach(x -> {
			String approach = x.getAsString("approach");

			if (approach.startsWith("l1")) {
				x.put("approach", "l1_" + replacement.computeIfAbsent(approach.substring(3), t -> t));
			} else {
				x.put("approach", replacement.computeIfAbsent(approach, t -> t));
			}
		});

		for (IKVStore store : col2) {
			List<Double> list = store.getAsDoubleList("n_par10");
			Collections.sort(list);
			for (int i = 0; i < TRIM_ELEMENTS; i++) {
				list.remove(0);
				list.remove(list.size() - 1);
			}
			store.put("tm_n_par10", ValueUtil.valueToString(StatisticsUtil.mean(list), 2));
		}

		KVStoreStatisticsUtil.best(col2, "scenario_name", "approach", "tm_n_par10", "best");
		col2.stream().forEach(x -> {
			if (x.getAsDouble("tm_n_par10") < 1) {
				x.put("tm_n_par10", "\\textbf{" + x.getAsString("tm_n_par10") + "}");
			}
		});

		col2.sort(new KVStoreSequentialComparator("scenario_name", "approach"));

		String latexTable2 = KVStoreUtil.kvStoreCollectionToLaTeXTable(col2, "scenario_name", "approach", "tm_n_par10");
		System.out.println(latexTable2);
	}

	public static double median(final Collection<? extends Number> values) {
		List<? extends Number> list = new ArrayList<>(values);
		list.sort(new Comparator<Number>() {
			@Override
			public int compare(final Number o1, final Number o2) {
				return Double.compare(o1.doubleValue(), o2.doubleValue());
			}
		});
		int upperIndex = (int) Math.ceil(((double) values.size() + 1) / 2);
		int lowerIndex = (int) Math.floor(((double) values.size() + 1) / 2);

		return (list.get(lowerIndex).doubleValue() + list.get(upperIndex).doubleValue()) / 2;
	}

}
