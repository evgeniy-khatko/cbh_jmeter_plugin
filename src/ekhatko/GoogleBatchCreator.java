/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package ekhatko;

import com.jcraft.jsch.*;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient;
import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Random;

public class GoogleBatchCreator extends AbstractJavaSamplerClient implements Serializable {
    private static final Logger LOG = LoggingManager.getLoggerForClass();
    private static final String DEFAULT_USER = "user";
    private static final String DEFAULT_SERVER = "host";
    private static final int DEFAULT_PORT = 22;
    private static final String DEFAULT_PASSWORD = "pwd";
    private static final String DEFAULT_LOCATION = "/tmp";
    private static final int DEFAULT_PERIOD = 10;
    private static final int DEFAULT_SIZE = 10;
    private static final long serialVersionUID = 240L;
    private static final String FILEPATH = System.getProperty("java.io.tmpdir");
    private static final String DEFAULT_BA = "MOCK_RU_DCB";
    private static final String DEFAULT_DISTR = "CHARGE=80, CANCEL=15, REFUND=5";
    private Map<Integer, String> distribution = new TreeMap<Integer,String>();
    private boolean badParams;
    
    private Stack<String> correlations = new Stack<String>();

    // The name of the sampler
    private String name;
    private long start = 0;
    private long period;
    private int size;
    
    /**
     * Default constructor for <code>GoogleBatchCreator</code>.
     *
     * The Java Sampler uses the default constructor to instantiate an instance
     * of the client class.
     */
    public GoogleBatchCreator() {
        LOG.debug(whoAmI() + "\tConstruct");
    }

    /**
     * @param context
     *            the context to run with. This provides access to
     *            initialization parameters.
     */
    @Override
    public void setupTest(JavaSamplerContext context) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(whoAmI() + "\tsetupTest()");
            listParameters(context);
        }
        name = context.getParameter(TestElement.NAME);       
        period = context.getLongParameter("Batch injection period");
        size = context.getIntParameter("Batch size");
        
        // requests distribution initialization
        for(String s : context.getParameter("Requests distribution").split(",")){
          if(s.matches("\\s*\\w+\\s*=\\s*\\d+\\s*")){
            String[] d = s.split("=");
            distribution.put(Integer.valueOf(d[1].trim()), d[0].trim());
          }
          else
            badParams = true;
        }
        start = System.currentTimeMillis();
    }

    /**
     * @param context
     *            the context to run with. This provides access to
     *            initialization parameters.
     *
     * @return a SampleResult giving the results of this sample.
     */
    @Override
    public SampleResult runTest(JavaSamplerContext context) {
        SampleResult results = new SampleResult();
        results.setSampleLabel(name);
        
        // Only do the calculation if it is needed
        try {
          // Record sample start time.
          results.sampleStart();
          if(badParams){
           results.setSuccessful(false);
           results.setResponseMessage("Bad parameters detected");
           results.setStopTestNow(true);
          }
          if((System.currentTimeMillis() - start) > period*1000){
            String path = createBatchFile(correlations, context.getParameter("Billing agreement"), distribution);            
            sendBatchFile(
                path, 
                context.getParameter("Username"),
                context.getParameter("Server"),
                context.getIntParameter("Port"),
                context.getParameter("Password"),
                context.getParameter("Remote location")
                );            
            correlations.clear();
            start = System.currentTimeMillis();
            results.setResponseMessage("processed: " + path);
          }
          else{
            String c = context.getParameter("New correlation");
            correlations.push(c);
            results.setResponseMessage("Added correlation: " + c);
          }
          results.setSuccessful(true);
        }catch (IOException e){
           LOG.error("GoogleBatch: file creation failure.");
           results.setSuccessful(false);
           results.setResponseMessage(e.toString());
           results.setStopTestNow(true);   
        /*
        }catch (InterruptedException e) {
           LOG.warn("GoogleBatch: interrupted.");
           results.setSuccessful(true);
           results.setResponseMessage(e.toString());
        */
        }catch (JSchException e){
           correlations.clear();
           LOG.error("GoogleBatch: ssh connection failure.");
           results.setSuccessful(false);
           results.setResponseMessage(e.toString());
           results.setStopTestNow(true);
        }catch (SftpException e){
           correlations.clear();
           LOG.error("GoogleBatch: scp failure.");
           results.setSuccessful(false);
           results.setResponseMessage(e.toString());        
        }catch (Exception e) {
           LOG.error("SleepTest: error during sample", e);
           results.setSuccessful(false);
           results.setResponseMessage(e.toString());
        }finally {
           results.sampleEnd();
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug(whoAmI() + "\trunTest()" + "\tTime:\t" + results.getTime());
            listParameters(context);
        }
        return results;
    }

    /**
     * @return a specification of the parameters used by this test which should
     *         be listed in the GUI, or null if no parameters should be listed.
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments params = new Arguments();
        params.addArgument("Username", DEFAULT_USER);
        params.addArgument("Server", DEFAULT_SERVER);
        params.addArgument("Port", String.valueOf(DEFAULT_PORT));
        params.addArgument("Password", DEFAULT_PASSWORD);
        params.addArgument("Remote location", DEFAULT_LOCATION);
        params.addArgument("Batch injection period", String.valueOf(DEFAULT_PERIOD));
        params.addArgument("Batch size", String.valueOf(DEFAULT_SIZE));
        params.addArgument("Billing agreement", DEFAULT_BA);
        params.addArgument("Requests distribution", DEFAULT_DISTR);
        params.addArgument("New correlation", "${cor}");
        return params;
    }

    private String createBatchFile(Stack<String> cors, String billingAgreement, Map<Integer,String> distribution) throws IOException{
      // "REQUESTFILE,#{Time.now.to_i},#{@file_id},#{@values['BA']}\n"
      String curTime = String.valueOf((System.currentTimeMillis() / 1000L));
      StringBuilder body = new StringBuilder(
          "REQUESTFILE"
          + "," + String.valueOf(curTime)
          + "," + String.valueOf(new Random().nextInt(10000000))
          + "," + billingAgreement
          + "\n"
          );
      // "#{type},#{Time.now.to_i},#{cor},#{@values['BA']}\n"
      int count = 0;
      Random rand = new Random();
      while(count < size && !cors.isEmpty()){
        String c = cors.pop();
        body.append(
            getType(rand, distribution)
            + "," + curTime
            + "," + c
            + "," + billingAgreement
            + "\n"
            );
      }
      // create file
      SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss"); 
      String filename = FILEPATH + File.separator +"request_" + formatter.format(new Date()) + ".csv";
      // Time.now.strftime("%Y%m%d%H%M%S")+'.csv'
      BufferedWriter bw = new BufferedWriter(new FileWriter(filename));
      bw.write(body.toString());
      bw.close();
      return filename;
    }
    
    private void sendBatchFile(String lPath, String user, String server, int port, String password, String rPath) throws JSchException, SftpException{
      JSch jsch = new JSch();
      JSch.setConfig("StrictHostKeyChecking", "no");
      Session session = jsch.getSession(user, server, port);
      session.setPassword(password);
      try{
        session.connect();
        Channel channel = session.openChannel("sftp");
        channel.connect();
        ChannelSftp cSftp = (ChannelSftp)channel;
        cSftp.put(lPath, rPath);
      }
      finally{
        if(session != null){
          session.disconnect();
        }
      }
    }
    
    private String getType(Random rand, Map<Integer, String> distribution){
      int max = Collections.max(distribution.keySet());
      for(Map.Entry<Integer, String> pair : distribution.entrySet()){
        if(rand.nextInt(max) < pair.getKey())
          return pair.getValue();
      }
      return null;
    }

    /**
     * Dump a list of the parameters in this context to the debug log.
     *
     * @param context
     *            the context which contains the initialization parameters.
     */
    private void listParameters(JavaSamplerContext context) {
        Iterator<String> argsIt = context.getParameterNamesIterator();
        while (argsIt.hasNext()) {
            String name = argsIt.next();
            LOG.debug(name + "=" + context.getParameter(name));
        }
    }

    /**
     * Generate a String identifier of this test for debugging purposes.
     *
     * @return a String identifier for this test instance
     */
    private String whoAmI() {
        StringBuilder sb = new StringBuilder();
        sb.append(Thread.currentThread().toString());
        sb.append("@");
        sb.append(Integer.toHexString(hashCode()));
        return sb.toString();
    }
}
