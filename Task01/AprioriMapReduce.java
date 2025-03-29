package Task01;

import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.*;

public class AprioriMapReduce {
    private static final int SUPPORT_THRESHOLD = 2;

    // Mapper Classes
    public static class AprioriFirstPassMapper extends Mapper<Object, Text, Text, IntWritable> {
        private IntWritable one = new IntWritable(1);
        private Text customerNumber = new Text();

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String[] tokens = value.toString().split(",");

            if (tokens.length < 2 ||
                    tokens[0].equalsIgnoreCase("Member_number")
            ) {
                return;
            }

            customerNumber.set(tokens[0]);

            // Emit (customer, count)
            context.write(customerNumber, one);
        }
    }
    public static class AprioriSecondPassMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final IntWritable one = new IntWritable(1);
        private Text customerPair = new Text();
        private static List<String> frequentCustomers = new ArrayList<>();

        @Override
        protected void setup(Context context) throws IOException {
            // Load the frequent customers from the distributed cache (output of first pass)
            URI[] cacheFiles = context.getCacheFiles();
            if (cacheFiles != null) {
                for (URI cacheFile : cacheFiles) {
                    BufferedReader reader = new BufferedReader(new FileReader(new Path(cacheFile.getPath()).getName()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Add customer ID from Pass 1 output
                        String customerID = line.split("\t")[0];
                        frequentCustomers.add(customerID);
                    }
                    reader.close();
                }
            }
        }

        @Override
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String[] customers = value.toString().split("\t")[1].split(",");

            List<String> validCustomers = new ArrayList<>();
            // Filter out customers who are not frequent (Pass 1 results)
            for (String customer : customers) {
                if (frequentCustomers.contains(customer)) {
                    validCustomers.add(customer);
                }
            }

            // Generate all pairs of frequent customers
            for (int i = 0; i < validCustomers.size(); i++) {
                for (int j = i + 1; j < validCustomers.size(); j++) {
                    String customer1 = validCustomers.get(i),
                            customer2 = validCustomers.get(j);

                    String pair = Integer.parseInt(customer1) <= Integer.parseInt(customer2)
                            ? customer1 + "," + customer2
                            : customer2 + "," + customer1;
                    customerPair.set(pair);
                    // Emit <pair, 1>
                    context.write(customerPair, one);
                }
            }
        }
    }

    // Reducer Classes
    public static class AprioriFirstPassReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
        private IntWritable count = new IntWritable();

        public void reduce(Text key, Iterable<IntWritable> values, Context context)
                throws IOException, InterruptedException {
            int sum = 0;

            for (IntWritable value : values) {
                sum++;
            }

            if (sum >= SUPPORT_THRESHOLD) {
                count.set(sum);
                // Emit (customer, 1)
                context.write(key, count);
            }
        }
    }
    public static class AprioriSecondPassReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
        private IntWritable count = new IntWritable();

        public void reduce(Text key, Iterable<IntWritable> values, Context context)
                throws IOException, InterruptedException {
            int sum = 0;

            for (IntWritable value : values) {
                sum++;
            }

            if (sum >= SUPPORT_THRESHOLD) {
                count.set(sum);
                context.write(key, count);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Configuration firstPassConf = new Configuration();
        Job firstPassJob = Job.getInstance(firstPassConf, "Apriori First Pass");

        firstPassJob.setJarByClass(AprioriMapReduce.class);
        firstPassJob.setMapperClass(AprioriFirstPassMapper.class);
        firstPassJob.setReducerClass(AprioriFirstPassReducer.class);

        firstPassJob.setMapOutputKeyClass(Text.class);
        firstPassJob.setMapOutputValueClass(IntWritable.class);

        FileInputFormat.addInputPath(firstPassJob, new Path(args[0]));
        FileOutputFormat.setOutputPath(firstPassJob, new Path(args[1]));

        if (!firstPassJob.waitForCompletion(true)) {
            System.exit(0);
        }

        Configuration secondPassConf = new Configuration();
        Job secondPassJob = Job.getInstance(secondPassConf, "Apriori Second Pass");

        secondPassJob.setJarByClass(AprioriMapReduce.class);
        secondPassJob.setMapperClass(AprioriSecondPassMapper.class);
        secondPassJob.setReducerClass(AprioriSecondPassReducer.class);

        secondPassJob.addCacheFile(new Path(args[1] + "/part-r-00000").toUri());
        secondPassJob.setMapOutputKeyClass(Text.class);
        secondPassJob.setMapOutputValueClass(IntWritable.class);

        FileInputFormat.addInputPath(secondPassJob, new Path(args[2]));
        FileOutputFormat.setOutputPath(secondPassJob, new Path(args[3]));

        System.exit(secondPassJob.waitForCompletion(true) ? 0 : 1);
    }
}