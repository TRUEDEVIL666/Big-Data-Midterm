import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.util.*;

public class GroupMapReduce {
    // Mapper Class
    public static class CustomerGroupByDateMapper extends Mapper<Object, Text, Text, Text> {
        private Text transactionDate = new Text();
        private Text customerNumber = new Text();

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String[] tokens = value.toString().split(",");

            if (tokens.length < 2 || tokens[0].equalsIgnoreCase("Member_number")) {
                return;
            }

            String number = tokens[0], date = tokens[1];

            // Emit (date, number)
            transactionDate.set(date);
            customerNumber.set(number);
            context.write(transactionDate, customerNumber);
        }
    }

    // Reducer Class
    public static class CustomerGroupByDateReducer extends Reducer<Text, Text, Text, Text> {
        private Text customersList = new Text();

        public void reduce(Text key, Iterable<Text> values, Context context) throws IOException, InterruptedException {
            Set<String> customerList = new HashSet<>();

            // Collect customer IDs for each date
            for (Text val : values) {
                customerList.add(val.toString());
            }

            String[] customerArray = customerList.toArray(new String[0]);

            // Join the customer IDs as a comma-separated string
            String customers = String.join(",", customerArray);

            customersList.set(customers);
            // Emit (shopping_date, list_of_customers)
            context.write(key, customersList);
        }
    }

    // Main Method
    public static void main(String[] args) throws Exception {
        Configuration conf = new Configuration();
        Job groupJob = Job.getInstance(conf, "Customer Date Groups");

        groupJob.setJarByClass(GroupMapReduce.class);

        groupJob.setMapperClass(CustomerGroupByDateMapper.class);
        groupJob.setCombinerClass(CustomerGroupByDateReducer.class);
        groupJob.setReducerClass(CustomerGroupByDateReducer.class);

        groupJob.setOutputKeyClass(Text.class);
        groupJob.setOutputValueClass(Text.class);

        FileInputFormat.addInputPath(groupJob, new Path(args[0]));
        FileOutputFormat.setOutputPath(groupJob, new Path(args[1]));

        System.exit(groupJob.waitForCompletion(true) ? 1 : 0);
    }
}