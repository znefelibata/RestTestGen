package io.resttestgen.implementation.sql.model;
import io.resttestgen.core.datatype.parameter.Parameter;

public class ParameterInstance {
    private final String path;
    private final String method;
    private final Parameter parameter;

    public ParameterInstance(String path, String method, Parameter parameter) {
        this.path = path;
        this.method = method;
        this.parameter = parameter;
    }

    public String getPath() { return path; }
    public String getMethod() { return method; }
    public Parameter getParameter() { return parameter; }
}
