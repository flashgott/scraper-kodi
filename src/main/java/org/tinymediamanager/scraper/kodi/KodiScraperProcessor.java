/*
 * Copyright 2012 - 2017 Manuel Laggner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tinymediamanager.scraper.kodi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class emulates the Kodi addon processing
 * 
 * @author Manuel Laggner, Myron Boyle
 */
class KodiScraperProcessor {
  public static final String  FUNCTION_SETTINGS = "GetSettings";
  private static final Logger LOGGER            = LoggerFactory.getLogger(KodiScraperProcessor.class);
  private static final int    PATTERN_OPTIONS   = Pattern.MULTILINE + Pattern.CASE_INSENSITIVE + Pattern.DOTALL;
  private boolean             truncateLogging   = true;
  private KodiScraper         scraper           = null;
  private String              buffers[]         = new String[21];

  public KodiScraperProcessor(KodiScraper scraper) {
    if (scraper == null)
      throw new RuntimeException("Scraper cannot be null!");

    this.scraper = scraper;

    LOGGER.debug("KodiScraperProcessor created using Scraper: " + scraper + "; Complete Logging: " + !truncateLogging);

    clearBuffers();
  }

  private KodiScraperProcessor(KodiScraper scraper, String[] buffers) {
    this.scraper = scraper;
    if (buffers != null) {
      for (int i = 0; i < buffers.length; i++) {
        this.buffers[i] = buffers[i];
      }
      // this.buffers = buffers;
    }
    else {
      clearBuffers();
    }
  }

  public String executeFunction(String function, String input[]) {
    ScraperFunction func = scraper.getFunction(function);

    if (func != null) {
      func = scraper.getFunction(function).clone(); // get as clone, since we are changing regexps!!!
      LOGGER.info("** BEGIN Function: " + func.getName() + "; Dest: " + func.getDest() + "; ClearBuffers: " + func.isClearBuffers());

      if (func.isClearBuffers()) {
        clearBuffers();
      }
      setBuffers(input);
      executeRegexps(func.getRegExps());

      LOGGER.info("** END Function: " + func.getName() + "; Dest: " + func.getDest() + "; ClearBuffers: " + func.isClearBuffers());
      return getBuffer(func.getDest());
    }
    else {
      LOGGER.warn("** Could not locate Function: " + function + " in the scraper " + scraper.getProviderInfo().getId());
      return "";
    }
  }

  private void executeRegexps(RegExp[] regExps) {
    int i = 0;
    for (RegExp r : regExps) {
      i++;
      LOGGER.trace("Executing " + i + "/" + regExps.length + " - " + r.getExpression());
      executeRegexp(r);
    }
  }

  private void executeRegexp(RegExp regex) {
    String cond = regex.getConditional();
    if (cond != null && !cond.isEmpty()) {
      boolean not = cond.startsWith("!");
      if (not) {
        cond = cond.substring(1);
      }
      // Boolean b = BooleanUtils.toBooleanObject(options.get(cond));
      Boolean b = scraper.getProviderInfo().getConfig().getValueAsBool(cond);

      LOGGER.trace("Processing Conditional: {} > {}", regex.getConditional(), (not ? !b : b));
      boolean b2 = (b == null || b.booleanValue() == true);
      if (!(b2 || (not && !b2))) {
        LOGGER.trace("Condition Not Met: {} > {}", regex.getConditional(), b2);
        return;
      }
    }

    if (regex.hasRegExps()) {
      executeRegexps(regex.getRegExps());
    }
    executeExpression(regex);
  }

  private void executeExpression(RegExp r) {
    LOGGER.trace(
        String.format("Processing Expression: %s; Dest: %s; Input: %s; Output: %s", r.getExpression(), r.getDest(), r.getInput(), r.getOutput()));
    Expression exp = r.getExpression();

    String in = getBuffer(r.getInput());
    if (in == null)
      in = "";

    if (exp == null) {
      LOGGER.warn("Main Expression was empty.  Returning processed output buffer using input as replacement array.");
      setBuffer(r.getDest(), processOutputBuffers(r.getOutput(), new String[] { "", in }), r.isAppendBuffer());
      return;
    }

    String expr = exp.getExpression();
    if (expr == null || expr.trim().length() == 0) {
      LOGGER.warn("Expression was empty.  Returning processed output buffer using input as replacement array.");
      setBuffer(r.getDest(), processOutputBuffers(r.getOutput(), new String[] { "", in }), r.isAppendBuffer());
      return;
    }

    LOGGER.trace("Expression: " + expr);
    expr = processOutputBuffersForInputBufferReferences(expr);
    LOGGER.trace("Expression: " + expr);
    LOGGER.trace("     Input: " + logBuffer(in));
    Pattern p = Pattern.compile(expr, PATTERN_OPTIONS);
    Matcher m = p.matcher(in);
    if (m.find()) {
      LOGGER.trace("Matched: Group Count: " + m.groupCount());
      setBuffer(r.getDest(), processOutputBuffers(r.getOutput(), toGroupArray(exp.getNoCleanArray(), m)), r.isAppendBuffer());

      if (exp.isRepeat()) {
        while (m.find()) {
          LOGGER.trace("Repeat Matched. Group Count: " + m.groupCount());
          setBuffer(r.getDest(), processOutputBuffers(r.getOutput(), toGroupArray(exp.getNoCleanArray(), m)), true); // repeat always append!
        }
      }
    }
    else {
      LOGGER.trace(String.format("No Match! Expression: %s; Text: %s;", expr, logBuffer(in)));
      if (exp.isClear()) {
        LOGGER.trace("Clearing Destination Buffer: " + r.getDest());
        setBuffer(r.getDest(), "", false);
      }
    }
  }

  private String logBuffer(String in) {
    // if debug is not enabled, then return the whole buffer.
    if (!LOGGER.isDebugEnabled()) {
      return in;
    }

    if (isTruncateLogging() && in != null && in.length() > 200) {
      in = "TRUNCATED(200): " + in.substring(0, 200) + "...";
    }
    return in;
  }

  private String[] toGroupArray(String noCleanArray[], Matcher groups) {
    int c = groups.groupCount();
    String g[] = new String[c + 1];
    for (int i = 0; i <= c; i++) {
      if (noCleanArray != null && noCleanArray[i] != null) {
        // don clean
        g[i] = groups.group(i);
      }
      else {
        g[i] = cleanHtml(groups.group(i));
      }
    }
    return g;
  }

  private String cleanHtml(String group) {
    if (group == null)
      return "";
    LOGGER.trace("Before Clean Html: " + group);
    // String s = group.replaceAll("<[^>]+>", "");
    String s = Jsoup.parse(group).text();
    LOGGER.trace("After  Clean Html: " + s);
    return s;
  }

  private String processOutputBuffers(String output, String groups[]) {
    LOGGER.trace("Processing output buffer replacement.");
    Pattern p = Pattern.compile("\\\\([0-9])");
    Matcher m = p.matcher(output);
    StringBuffer sb = new StringBuffer();

    int lastStart = 0;
    while (m.find()) {
      sb.append(output.substring(lastStart, m.start()));
      lastStart = m.end();
      int g = Integer.parseInt(m.group(1));
      if (g > groups.length) {
        LOGGER.trace("No Group Replacement for: " + g);
        continue;
      }

      // TODO: check noClean flag, and clean otherwise
      int index = Integer.parseInt(m.group(1));
      String val = "";
      if (index < groups.length) {
        val = groups[index];
      }
      if (val == null)
        val = "";
      sb.append(val);
    }

    sb.append(output.substring(lastStart));

    return processOutputBuffersForPropertyReferences(processOutputBuffersForInputBufferReferences(sb.toString()));
  }

  private String processOutputBuffersForInputBufferReferences(String output) {
    LOGGER.trace("Processing buffer for input buffer references.");
    Pattern p = Pattern.compile("\\$\\$([0-9]+)");
    Matcher m = p.matcher(output);
    StringBuffer sb = new StringBuffer();

    int lastStart = 0;
    while (m.find()) {
      sb.append(output.substring(lastStart, m.start()));
      lastStart = m.end();
      sb.append(getBuffer(Integer.parseInt(m.group(1))));
    }

    sb.append(output.substring(lastStart));

    return sb.toString();
  }

  private String processOutputBuffersForPropertyReferences(String output) {
    LOGGER.trace("Processing buffer for property references.");
    Pattern p = Pattern.compile("\\$INFO\\[([^\\]]+)\\]");
    Matcher m = p.matcher(output);
    StringBuffer sb = new StringBuffer();

    int lastStart = 0;
    while (m.find()) {
      sb.append(output.substring(lastStart, m.start()));
      lastStart = m.end();
      sb.append(scraper.getProviderInfo().getConfig().getValue(m.group(1)));
    }

    sb.append(output.substring(lastStart));

    return sb.toString();
  }

  // private String processOutputBuffersForChaining(String output) {
  // log.debug("Processing output buffers for chaining.");
  // Pattern p = Pattern.compile("<chain function=\"(.*)\">(.*)</chain>");
  // Matcher m = p.matcher(output);
  // StringBuffer sb = new StringBuffer();
  //
  // int lastStart = 0;
  // while (m.find()) {
  // String function = m.group(1);
  // String buffer = m.group(2);
  // XbmcScraperProcessor proc = new XbmcScraperProcessor(scraper);
  // // sb.append("<" + function + ">" + proc.executeFunction(function, new
  // // String[] { "", buffer }) + "</" + function + ">");
  // sb.append(proc.executeFunction(function, new String[] { "", buffer }));
  // }
  //
  // if (sb.length() == 0) {
  // sb.append(output);
  // }
  //
  // return sb.toString();
  // }

  private String getBuffer(int buffer) {
    String text = buffers[buffer];
    if (text == null) {
      text = "";
    }
    text = KodiUtil.fixXmlHeader(text); // fix possible XML header errors
    text = processOutputBuffersForPropertyReferences(text); // replace $INFO vars

    LOGGER.trace("Get Int Buffer: " + buffer + "; Text: " + logBuffer(text));
    return text;
  }

  private String getBuffer(String buffer) {
    if (buffer == null) {
      buffer = "";
    }
    buffer = KodiUtil.fixXmlHeader(buffer); // fix possible XML header errors
    buffer = processOutputBuffersForPropertyReferences(buffer); // replace $INFO vars

    LOGGER.trace(String.format("Get String Buffer: %s", buffer));
    Pattern bufferPattern = Pattern.compile("\\$\\$([0-9]+)");
    Matcher m = bufferPattern.matcher(buffer);
    if (m.find()) {
      StringBuffer sb = new StringBuffer();
      sb.append(getBuffer(Integer.parseInt(m.group(1))));
      while (m.find()) {
        sb.append(getBuffer(Integer.parseInt(m.group(1))));
      }
      return sb.toString();
    }
    else {
      LOGGER.trace("getBuffer(): Using raw input: " + logBuffer(buffer));
    }
    return buffer;
  }

  private void setBuffer(int buffer, String text, boolean append) {
    if (text == null) {
      text = "";
    }

    LOGGER.trace(String.format("Set Buffer: %s; Append: %s; Text: %s", buffer, append, logBuffer(text)));

    Pattern p = Pattern.compile("<url\\s+.*function=");
    Matcher m = p.matcher(text);
    if (m.find()) {
      LOGGER.debug("Processing Sub Function URL: " + text);
      try {
        KodiUrl url = new KodiUrl(text);
        ScraperFunction func = scraper.getFunction(url.getFunctionName());
        if (func == null) {
          throw new Exception("Invalid Function Name: " + url.getFunctionName());
        }
        KodiScraperProcessor proc = newSubProcessor(func.isClearBuffers());

        // call the set buffer again with this result
        text = proc.executeFunction(url.getFunctionName(), new String[] { "", url.getTextContent() });
        append = true; // always append sub functions!
      }
      catch (Exception e) {
        LOGGER.error("Failed to process function: " + text, e);
        text = "\n<error>" + text + "\n<msg>" + e.getMessage() + "</msg></error>\n";
      }
    }

    // sub Function
    p = Pattern.compile("<chain function=\"(.*)\">(.*)</chain>");
    m = p.matcher(text);
    if (m.find()) {
      LOGGER.debug("Processing Sub Function CHAIN: " + text);
      try {
        ScraperFunction func = scraper.getFunction(m.group(1));
        if (func == null) {
          throw new Exception("Invalid Function Name: " + m.group(1));
        }
        KodiScraperProcessor proc = newSubProcessor(func.isClearBuffers());

        // call the set buffer again with this result
        text = "<" + m.group(1) + ">" + proc.executeFunction(m.group(1), new String[] { "", m.group(2) }) + "</" + m.group(1) + ">";
        append = true; // always append sub functions!
      }
      catch (Exception e) {
        LOGGER.error("Failed to process function: " + text, e);
        text = "\n<error>" + text + "\n<msg>" + e.getMessage() + "</msg></error>\n";
      }
    }

    if (append) {
      String s = buffers[buffer];
      if (s != null) {
        LOGGER.trace("Appending to buffer: " + buffer);
        text = s + text;
      }
    }
    buffers[buffer] = text;
  }

  public void clearBuffers() {
    for (int i = 0; i < buffers.length; i++) {
      setBuffer(i, "", false);
    }
  }

  private void setBuffers(String[] input) {
    if (input == null)
      return;
    LOGGER.trace("Set Buffers: # of input Buffers: " + input.length);
    for (int i = 0; i < input.length; i++) {
      if (input[i] != null)
        setBuffer(i, input[i], false);
    }
  }

  public boolean containsFunction(String functionName) {
    return scraper.containsFunction(functionName);
  }

  public KodiScraper getScraper() {
    return scraper;
  }

  public boolean isTruncateLogging() {
    return truncateLogging;
  }

  public void setTruncateLogging(boolean truncateLogging) {
    this.truncateLogging = truncateLogging;
  }

  /**
   * Return a copy of this processor. Clear the buffers if necessary.
   * 
   */
  public KodiScraperProcessor newSubProcessor(boolean clearBuffers) {
    return new KodiScraperProcessor(scraper, (clearBuffers) ? null : buffers);
  }
}
