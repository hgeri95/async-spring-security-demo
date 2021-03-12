# Why you shouldn't use Spring SecurityContextHolder MODE_INHERITABLETHREADLOCAL with ThreadPool in you project?

Maybe you already use some async functions in your spring projects as we do. We are using Spring WebFlux. This means, that we have some async executions, so we had to find a solution to propagate the SecurityContext properly. Read the first chapter to understand why.

## The base problem with SecurityContextHolder in async environment

Maybe you already read it many places, that Spring Authentication is bound to a ThreadLocal, so when the execution flow runs in a 
new thread with @Async, you will lose your authenticated context. This means, that if you call SecurityContextHolder.getContext(),
you will get a great big NPE (NullPointerException).

## Possible solution

In many articles and comments you will find the following as a solution. You just have to set the SecurityContextHolder strategy 
to MODE_INHERITABLETHREADLOCAL. Like this:

```java
@PostConstruct
public void enableAuthCtxOnSpawnedThreads() {
    SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
}
```
If you write a simple test, which performs a simple call, it will solve your problem. Maybe you will be satisfied with it 
for a long time in production as well.

But there is a closed ticket in Spring Security GitHub, where you can read about a discussion why this is not a solution,
when you are using ThreadPool as well. (https://github.com/spring-projects/spring-security/issues/6856) This is pretty interesting, but
a bit hard to understand. And I never read any Spring documentation, which mentioned such a problem. 

So I started to look into the spring implementation.
Without going into the details I found, that the mentioned strategy and the DelegatingSecurityContextAsyncTaskExecutor doing two 
different things. The MODE_INHERITABLETHREADLOCAL strategy clears the context, when you call the SecurityContextHolder.clearContext()
method. While the DelegatingSecurityContextAsyncTaskExecutor calls the clearContext() after an execution is finished. Based on this
I had a theory that the mentioned problem in the ticket is a real problem, because in some cases the ThreadPool will contain
Threads, where the SecurityContext is already set for a user and it's not cleared, so it can cause some mess. In the next chapter, I will show you 
a basic example how I reproduced the issue and tried out different solutions.

## Test and prove

*You can skip this chapter if you are not interested in the proof.*

I created a small Spring demo app with Spring Security and with an @Async method.
I have an AuthenticationFilter, which is very primitive, but it works as most of the security filters, without token validation.

```java
@Component
public class DemoAuthenticationFilter extends BasicAuthenticationFilter {

    public DemoAuthenticationFilter(AuthenticationManager authenticationManager) {
        super(authenticationManager);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        Authentication authentication = new DemoAuthentication(authHeader, true);

        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
        SecurityContextHolder.clearContext();
    }
}
```

I just want to send the username as Authorization header and this will be saved into the SecurityContext. After the chain execution
the SecurityContext will be cleared. (You can find many examples, where you will see similar patterns)

What I need is a security config, where I can define my "security" filter.
```java
@EnableWebSecurity
@Configuration
@EnableGlobalMethodSecurity(securedEnabled = true)
public class WebSecurity extends WebSecurityConfigurerAdapter {

    public WebSecurity() {
        super(true);
    }

    @Bean
    @Override
    public AuthenticationManager authenticationManagerBean() throws Exception {
        return super.authenticationManagerBean();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .anyRequest().authenticated().and().
                exceptionHandling()
                .and().addFilterBefore(new DemoAuthenticationFilter(authenticationManagerBean()), UsernamePasswordAuthenticationFilter.class);
    }
}
```

Let's create a simple REST controller, where the username will be a request parameter and based on an asnyc call it will give
back the username from the SecurityContextHolder inside the async method.
```java
@RestController
public class DemoController {

 @Autowired
 DemoService demoService;

 @SneakyThrows
 @RequestMapping("/name/{username}")
 public String doSomethingA(@PathVariable String username) {
  return demoService.hello(username).get();
 }
}
```
The service with the async method is the following.
```java
@Service
@Slf4j
public class DemoService {

    @Async
    public Future<String> hello(String username) throws InterruptedException {
        String usernameFromContext = SecurityContextHolder.getContext().getAuthentication().getName();
        log.info("Username param: {} and context: {}", username, usernameFromContext);
        Thread.sleep(10);
        return new AsyncResult<String>(usernameFromContext);
    }
}
```

### Test case #1
Just test it with the defaults, so I don't want to overwrite the default SecurityContextHolder strategy, so the config is 
pretty simple.
```java
@Configuration
@EnableAsync
@Profile("simple")
public class DemoConfigurationSimple {
}
```

And the test will perform a single call. (In the application.properties I set the profile to *simple*)
```java
 @Test
 void testSimpleCall() {
     final String username = "Joe";
     RequestEntity requestEntity = generateRequest(username);
     ResponseEntity<String> response = this.restTemplate.exchange(requestEntity, String.class);
     assertEquals(username, response.getBody());
 }
```

**Result:**  java.lang.NullPointerException: null as we expected.

### Test case #2

Now I will use the MODE_INHERITABLETHREADLOCAL strategy. (Profile: *inheritable*)
```java
@Configuration
@EnableAsync
@Profile({"inheritable"})
public class DemoConfigurationInheritable {

    @PostConstruct
    public void enableAuthCtxOnSpawnedThreads() {
        SecurityContextHolder.setStrategyName(SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
    }
}
```

If I run the previous test the result will be green. But what's the situation, if I perform paralell calls, like this:

```java
@Test
@SneakyThrows
void testSecurityContextInAsyncEnvironmentWithThreadPool() {
    Thread threadA = generateThread("Timon"); // Thread to call endpoint /A 500 times
    Thread threadB = generateThread("Pumbaa"); // Thread to call endpoint /B 500 times
    threadA.start();
    threadB.start();
    threadA.join();
    threadB.join();
}

private Thread generateThread(String username) {
    return new Thread(() -> {
        RequestEntity requestEntity = generateRequest(username);
        for (int i = 0; i < 500; i++) {
            ResponseEntity<String> response = this.restTemplate.exchange(requestEntity, String.class);
            assertEquals(username, response.getBody());
        }
    });
}
```

For some time it will work fine, but in some cases the name in the parameter and the name in the context will be different.
```aidl
2021-03-12 00:14:16.769  INFO 5128 --- [         task-2] c.e.a.service.DemoService                : Username param: Timon and context: Timon
2021-03-12 00:14:16.769  INFO 5128 --- [         task-1] c.e.a.service.DemoService                : Username param: Pumbaa and context: Pumbaa
2021-03-12 00:14:16.833  INFO 5128 --- [         task-4] c.e.a.service.DemoService                : Username param: Timon and context: Timon
2021-03-12 00:14:16.833  INFO 5128 --- [         task-3] c.e.a.service.DemoService                : Username param: Pumbaa and context: Pumbaa
2021-03-12 00:14:16.849  INFO 5128 --- [         task-5] c.e.a.service.DemoService                : Username param: Pumbaa and context: Pumbaa
2021-03-12 00:14:16.849  INFO 5128 --- [         task-6] c.e.a.service.DemoService                : Username param: Timon and context: Timon
2021-03-12 00:14:16.879  INFO 5128 --- [         task-8] c.e.a.service.DemoService                : Username param: Pumbaa and context: Pumbaa
2021-03-12 00:14:16.879  INFO 5128 --- [         task-7] c.e.a.service.DemoService                : Username param: Timon and context: Timon
2021-03-12 00:14:16.895  INFO 5128 --- [         task-1] c.e.a.service.DemoService                : Username param: Timon and context: Pumbaa
2021-03-12 00:14:16.895  INFO 5128 --- [         task-2] c.e.a.service.DemoService                : Username param: Pumbaa and context: Timon
Exception in thread "Thread-3" Exception in thread "Thread-2" org.opentest4j.AssertionFailedError: expected: <Pumbaa> but was: <Timon>
```
**Result:** As you can see the problem raising in the introduction was real.

### Test case #3
Many pages will mention another solution for the original problem, so let's use the DelegatingSecurityContextAsyncTaskExecutor and
run the multi threaded test again. I used the following config with profile *delegating*:
```java
@Configuration
@EnableAsync
@Profile({"delegating"})
public class DemoConfigurationDelegating {
    @Bean
    public TaskExecutor threadPoolTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("Thread-");
        executor.initialize();

        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }
}
```

**Result:** This will work as expected. The username is never mixed up, so my assumption was true.

## Conclusion
If you want to use async functions in your Spring project and ThreadPool (which is the default), in the same time and 
you need a correct SecurityContext during the asnyc execution you should use the DelegatingSecurityContextAsyncTaskExecutor
instead the MODE_INHERITABLETHREADCONTEXT strategy.


#### *References:*

- https://www.baeldung.com/spring-security-async-principal-propagation
- https://github.com/spring-projects/spring-security/issues/6856
- https://stackoverflow.com/questions/3467918/how-to-set-up-spring-security-securitycontextholder-strategy
- https://github.com/spring-projects/spring-security/issues/6856#issuecomment-518787966
