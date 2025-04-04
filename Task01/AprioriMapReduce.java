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
        private final IntWritable one = new IntWritable(1);
        private Text customerNumber = new Text();

        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String[] tokens = value.toString().split("\t");

            if (tokens.length < 2) {
                return;
            }

            String[] customers = tokens[1].split(",");
            for (String customer : customers) {
                customerNumber.set(customer);
                // Emit (customer, count)
                context.write(customerNumber, one);
            }
        }
    }

    public static class AprioriSecondPassMapper extends Mapper<Object, Text, Text, IntWritable> {
        private final IntWritable one = new IntWritable(1);
        private Text customerPair = new Text();
        private static Set<String> frequentCustomers = new HashSet<>();

        @Override
        protected void setup(Context context) throws IOException {
            // Load the frequent customers from the distributed cache (output of first pass)
            URI[] cacheFiles = context.getCacheFiles();
            if (cacheFiles != null) {
                for (URI cacheFile : cacheFiles) {
                    try (BufferedReader reader = new BufferedReader(new FileReader(new Path(cacheFile.getPath()).getName()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // Read customer ID from the first pass output
                            String customerID = line.split("\t")[0];
                            frequentCustomers.add(customerID);
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        @Override
        public void map(Object key, Text value, Context context) throws IOException, InterruptedException {
            String[] customers = value.toString().split("\t")[1].split(",");

            // Generate all pairs of frequent customers
            for (int i = 0; i < customers.length; i++) {
                String customer1 = customers[i];
                // Filter out customers who are not frequent (Pass 1 results)
                if (frequentCustomers.contains(customer1)) {
                    for (int j = i+1; j < customers.length; j++) {
                        String customer2 = customers[j];
                        if (frequentCustomers.contains(customer2)) {
                            String pair = (customer1.compareTo(customer2) < 0)
                                    ? customer1 + "," + customer2
                                    : customer2 + "," + customer1;

                            customerPair.set(pair);
                            context.write(customerPair, one);
                        }
                    }
                }
            }
        }
    }

    // Reducer Class
    public static class AprioriReducer extends Reducer<Text, IntWritable, Text, IntWritable> {
        private IntWritable count = new IntWritable();

        public void reduce(Text key, Iterable<IntWritable> values, Context context) throws IOException, InterruptedException {
            int sum = 0;
            for (IntWritable value : values) {
                sum += value.get();
            }

            if (sum >= SUPPORT_THRESHOLD) {
                count.set(sum);
                context.write(key, count);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Job firstPassJob = createJob(
                "Apriori First Pass",
                AprioriFirstPassMapper.class,
                args[0], args[1]
        );

        if (!firstPassJob.waitForCompletion(true)) {
            System.exit(0);
        }

        Job secondPassJob = createJob(
                "Apriori Second Pass",
                AprioriSecondPassMapper.class,
                args[0], args[2]
        );

        secondPassJob.addCacheFile(new Path(args[1] + "/part-r-00000").toUri());

        System.exit(secondPassJob.waitForCompletion(true) ? 0 : 1);
    }

    private static <T extends Mapper<?, ?, ?, ?>> Job createJob(String jobName, Class<T> mapperClass, String inputPath, String outputPath) throws Exception {
        Configuration conf = new Configuration();
        Job job = Job.getInstance(conf, jobName);
        job.setJarByClass(AprioriMapReduce.class);
        job.setMapperClass(mapperClass);
        job.setCombinerClass(AprioriReducer.class);
        job.setReducerClass(AprioriReducer.class);
        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(IntWritable.class);
        FileInputFormat.addInputPath(job, new Path(inputPath));
        FileOutputFormat.setOutputPath(job, new Path(outputPath));
        return job;
    }
}