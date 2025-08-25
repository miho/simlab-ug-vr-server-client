package com.simlab.ug.common;

import com.simlab.ug.grpc.*;
//import org.luaj.vm2.*;
//import org.luaj.vm2.lib.jse.JsePlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LuaScriptParser {
    private static final Logger logger = LoggerFactory.getLogger(LuaScriptParser.class);
    
    private static final Pattern UTIL_GET_PARAM = Pattern.compile(
            "util\\.GetParam\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*([^,)]+?)(?:\\s*,\\s*[\"']([^\"']+)[\"'])?(?:\\s*,\\s*\\{[^}]*\\})?\\s*\\)",
            Pattern.MULTILINE | Pattern.DOTALL
    );
    
    private static final Pattern UTIL_GET_PARAM_NUMBER = Pattern.compile(
            "util\\.GetParamNumber\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*([^,)]+?)(?:\\s*,\\s*[\"']([^\"']+)[\"'])?(?:\\s*,\\s*\\{[^}]*\\})?\\s*\\)",
            Pattern.MULTILINE | Pattern.DOTALL
    );
    
    private static final Pattern UTIL_GET_PARAM_BOOL = Pattern.compile(
            "util\\.GetParamBool\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*,\\s*([^,)]+?)(?:\\s*,\\s*[\"']([^\"']+)[\"'])?(?:\\s*,\\s*\\{[^}]*\\})?\\s*\\)",
            Pattern.MULTILINE | Pattern.DOTALL
    );
    
    private static final Pattern VARIABLE_ASSIGNMENT = Pattern.compile(
            "(\\w+)\\s*=\\s*util\\.Get(?:Param|ParamNumber|ParamBool)\\s*\\([^)]+\\)",
            Pattern.MULTILINE
    );
    
    public ScriptAnalysis analyzeScript(File scriptFile) {
        ScriptAnalysis.Builder analysis = ScriptAnalysis.newBuilder();
        List<ScriptParameter> parameters = new ArrayList<>();
        
        try {
            String scriptContent = readFile(scriptFile);
            
            // Parse util.GetParamNumber calls first (numeric parameters)
            Matcher numberMatcher = UTIL_GET_PARAM_NUMBER.matcher(scriptContent);
            while (numberMatcher.find()) {
                String paramName = numberMatcher.group(1);
                String defaultValue = numberMatcher.group(2);
                String description = numberMatcher.group(3);
                
                // Skip if already added
                if (parameters.stream().anyMatch(p -> p.getName().equals(paramName))) {
                    continue;
                }
                
                ScriptParameter.Builder param = ScriptParameter.newBuilder()
                        .setName(paramName)
                        .setType(ParameterType.FLOAT)
                        .setDescription(description != null ? description : "");
                
                try {
                    defaultValue = defaultValue.trim();
                    double value = Double.parseDouble(defaultValue);
                    if (value == Math.floor(value)) {
                        param.setType(ParameterType.INTEGER);
                        param.setIntValue((int) value);
                    } else {
                        param.setFloatValue(value);
                    }
                } catch (NumberFormatException e) {
                    // Try to evaluate if it's a simple expression
                    param.setType(ParameterType.INTEGER);
                    param.setIntValue(3); // Default value
                }
                
                parameters.add(param.build());
            }
            
            // Parse util.GetParamBool calls (boolean parameters)
            Matcher boolMatcher = UTIL_GET_PARAM_BOOL.matcher(scriptContent);
            while (boolMatcher.find()) {
                String paramName = boolMatcher.group(1);
                String defaultValue = boolMatcher.group(2);
                String description = boolMatcher.group(3);
                
                // Skip if already added
                if (parameters.stream().anyMatch(p -> p.getName().equals(paramName))) {
                    continue;
                }
                
                ScriptParameter.Builder param = ScriptParameter.newBuilder()
                        .setName(paramName)
                        .setType(ParameterType.BOOLEAN)
                        .setDescription(description != null ? description : "");
                
                param.setBoolValue("true".equalsIgnoreCase(defaultValue.trim()));
                
                parameters.add(param.build());
            }
            
            // Parse util.GetParam calls last (string parameters)
            Matcher paramMatcher = UTIL_GET_PARAM.matcher(scriptContent);
            while (paramMatcher.find()) {
                String paramName = paramMatcher.group(1);
                String defaultValue = paramMatcher.group(2);
                String description = paramMatcher.group(3);
                
                // Skip if this parameter name was already found in GetParamNumber or GetParamBool
                if (parameters.stream().anyMatch(p -> p.getName().equals(paramName))) {
                    continue;
                }
                
                ScriptParameter.Builder param = ScriptParameter.newBuilder()
                        .setName(paramName)
                        .setType(ParameterType.STRING)
                        .setDescription(description != null ? description : "");
                
                // Clean up default value
                defaultValue = defaultValue.trim();
                if (defaultValue.startsWith("\"") && defaultValue.endsWith("\"")) {
                    defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
                } else if (defaultValue.startsWith("'") && defaultValue.endsWith("'")) {
                    defaultValue = defaultValue.substring(1, defaultValue.length() - 1);
                }
                // Handle concatenated strings or complex expressions
                if (defaultValue.contains("..") || defaultValue.contains("+")) {
                    // For complex expressions, just use a placeholder
                    defaultValue = "default_value";
                }
                param.setStringValue(defaultValue);
                
                parameters.add(param.build());
            }
            
            // Also check for hardcoded commonly used parameters
            if (scriptContent.contains("-grid") && parameters.stream().noneMatch(p -> p.getName().equals("-grid"))) {
                parameters.add(ScriptParameter.newBuilder()
                        .setName("-grid")
                        .setType(ParameterType.STRING)
                        .setStringValue("mygrid.ugx")
                        .setDescription("Grid file to use")
                        .build());
            }
            
            analysis.setSuccess(true);
            analysis.addAllParameters(parameters);
            
        } catch (Exception e) {
            logger.error("Error analyzing script: " + scriptFile, e);
            analysis.setSuccess(false);
            analysis.setErrorMessage("Failed to analyze script: " + e.getMessage());
        }
        
        return analysis.build();
    }
    
    private String readFile(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (FileReader reader = new FileReader(file)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        }
        return content.toString();
    }
}