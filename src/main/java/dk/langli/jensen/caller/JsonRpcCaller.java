package dk.langli.jensen.caller;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.TypeFactory;

import dk.langli.jensen.JsonRpcResponse;
import dk.langli.jensen.Request;
import dk.langli.jensen.broker.JsonRpcIgnore;
import ru.vyarus.java.generics.resolver.GenericsResolver;

public class JsonRpcCaller {
    private final Transport transport;
    private final SortedSet<Integer> idSeq;
    private final ObjectMapper objectMapper;
    
    public static JsonRpcCallerBuilder builder() {
        return new JsonRpcCallerBuilder();
    }
    
    public JsonRpcCaller(JsonRpcCallerBuilder builder) {
        idSeq = new TreeSet<>();
        transport = builder.getTransport();
        objectMapper = builder.getObjectMapper() != null ? builder.getObjectMapper() : new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(JsonRpcException.class, new JsonRpcExceptionDeserializer(objectMapper));
        objectMapper.registerModule(module);
    }
    
    public <T> T call(String method, Type returnType, Object... params) throws JsonRpcException, TransportException {
        T returnValue = null;
        if(params == null) {
            params = new Object[0];
        }
        Integer id = nextId();
        Request request = new Request(id, method, Arrays.asList(params));
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            String responseJson = transport.send(requestJson);
            if(responseJson != null) {
                JsonRpcResponse response = objectMapper.readValue(responseJson, JsonRpcResponse.class);
                if(response.getError() != null) {
                	Object data = response.getError().getData();
                    JsonRpcException e = objectMapper.convertValue(data, JsonRpcException.class);
                    throw e;
                }
                else {
                    if(returnType != null && !returnType.getTypeName().equals("void")) {
                        JavaType typeReference = TypeFactory.defaultInstance().constructType(returnType);
                        returnValue = objectMapper.convertValue(response.getResult(), typeReference);
                    }
                }
            }
        }
        catch(JsonRpcException e) {
            throw e;
        }
        catch(TransportException e) {
            throw e;
        }
        catch(JsonMappingException e) {
            throw new JsonRpcException(e);
        }
        catch(Exception e) {
            throw new JsonRpcException(e);
        }
        finally {
            if(id != null) {
                removeId(id);
            }
        }
        return returnValue;
    }
    
    private Integer nextId() {
        Integer id = 1;
        synchronized(idSeq) {
            Iterator<Integer> idSeqIterator = idSeq.iterator();
            while(idSeqIterator.hasNext() && id == idSeqIterator.next()) {
                id++;
            }
            idSeq.add(id);
        }
        return id;
    }
    
    private void removeId(Integer id) {
        synchronized(idSeq) {
            idSeq.remove(id);
        }
    }

    public <T> T callThis(Object... params) throws JsonRpcException, TransportException {
        return call(2, params);
    }

	public <T> T call(int callStackDistance, Object... params) throws JsonRpcException, TransportException {
		StackTraceElement callStackSubject = new Throwable().getStackTrace()[callStackDistance];
		String className = callStackSubject.getClassName();
		String methodName = callStackSubject.getMethodName();
		try {
			Class<?> type = Class.forName(className);
			className = findImplClassname(type);
		}
		catch(SecurityException | ClassNotFoundException e) {
			throw new JsonRpcException(e);
		}
		return callMethod(className, methodName, params);
	}
	
    @SuppressWarnings("unchecked")
    public <T> T callMethod(String className, String methodName, Object... params) throws JsonRpcException, TransportException {
        Type returnType = null;
        String fqMethodName = null;
        try {
            Class<?> type = Class.forName(className);
            List<Method> methods = Arrays.asList(type.getMethods());
            Method method = methods.stream()
            		.filter(m -> m.getName().equals(methodName))
           			.filter(m -> m.getAnnotation(JsonRpcIgnore.class) == null)
            		.filter(m -> m.getParameterCount() == (params != null ? params.length : 0))
            		.filter(m -> {
            			Class<?>[] paramTypes = m.getParameterTypes();
            			for(int i=0; i<paramTypes.length; i++) {
            				if(!paramTypes[i].isInstance(params[i])) {
            					return false;
            				}
            			}
            			return true;
            		})
            		.findFirst()
            		.orElseThrow(() -> new NoSuchMethodException(String.format("No method %s with the given parameters", methodName)));
            returnType = GenericsResolver.resolve(type).method(method).resolveReturnType();
            fqMethodName = className+"."+methodName;
        }
        catch(SecurityException | ClassNotFoundException | NoSuchMethodException e) {
            throw new JsonRpcException(e);
        }
        return (T) call(fqMethodName, returnType, params);
    }
    
    private String findImplClassname(Class<?> type) {
   	 String classname = type.getName();
   	 JsonRpcImpl impl = type.getAnnotation(JsonRpcImpl.class);
   	 if(impl != null) {
   		 classname = impl.remote().getName();
   	 }
   	 return classname;
    }
}
