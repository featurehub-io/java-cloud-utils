package cd.connect.jersey.prometheus;

import io.prometheus.client.Histogram;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedMap;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static cd.connect.jersey.prometheus.GlobalJerseyMetrics.response_2xx;
import static cd.connect.jersey.prometheus.GlobalJerseyMetrics.response_3xx;
import static cd.connect.jersey.prometheus.GlobalJerseyMetrics.response_4xx;
import static cd.connect.jersey.prometheus.GlobalJerseyMetrics.response_5xx;

/**
 * @author Richard Vowles
 */
@Priority(Priorities.HEADER_DECORATOR)
public class PrometheusFilter implements ContainerRequestFilter, ContainerResponseFilter {


	private static final String TRACKER_TIMER = "prometheus.timer";

  protected final ResourceInfo resourceInfo;
  protected String prefix;
  private final Prometheus annotation;
  private CombinationDumpling tracker;
	// don't track the timer, only the result
	private final boolean noTimer;
  private static final Map<String, CombinationDumpling> metrics = new ConcurrentHashMap<>();


	private static class CombinationDumpling {
		public Histogram histogram;

		public static CombinationDumpling findOrCreate(Prometheus annotation) {
			return findOrCreate(annotation.name(), annotation.help());
		}

		public static CombinationDumpling findOrCreate(String name, String help) {
			return metrics.computeIfAbsent(name, k -> new CombinationDumpling(name, help));
		}

		public CombinationDumpling(String name, String help) {
			histogram = Histogram.build(name+"_histogram", help + " Histogram").register();
		}

		LiteDumpling startTimer() {
			return new LiteDumpling(histogram.startTimer());
		}
	}

	public static class LiteDumpling {
		private final Histogram.Timer hTimer;

		public LiteDumpling(Histogram.Timer hTimer) {
			this.hTimer = hTimer;
		}

		public void observe() {
			hTimer.observeDuration();
		}
	}

  /**
   * Registers a filter specifically for the defined method.
   *
   * @param resourceInfo - the resource (uri to class + method) we are registering this filter for
   * @param prefix - the prefix we should apply to all metrics (if any)
   * @param annotation - one if it exists
   */
	public PrometheusFilter(ResourceInfo resourceInfo, String prefix, Prometheus annotation, boolean noTimer) {
    this.resourceInfo = resourceInfo;
    this.prefix = prefix;
    this.annotation = annotation;
		this.noTimer = noTimer;

		if (!noTimer && annotation != null) {
			buildTimerFromAnnotation(annotation);
		}
	}

  /**
   * if the annotation is fully specified, use it.
   *
   * @param annotation - provides us a name and help
   */
  private void buildTimerFromAnnotation(Prometheus annotation) {
    if (annotation != null && annotation.help().length() > 0 && annotation.name().length() > 0) {
      tracker = CombinationDumpling.findOrCreate(annotation);
    }
  }

  @Override
  public void filter(ContainerRequestContext requestContext) {
	  if (!noTimer && tracker == null) { // we only need to do this once
      buildTracker(requestContext);
    }

    if (tracker != null) {
      requestContext.setProperty(TRACKER_TIMER, tracker.startTimer());
    }
  }

  private void buildTracker(ContainerRequestContext requestContext) {
    String path = annotation == null ? "" : annotation.help();

    if (path.length() == 0) {
      // this won't change from request to request
      MultivaluedMap<String, String> pathParameters = requestContext.getUriInfo().getPathParameters();

      path = path(requestContext.getUriInfo().getRequestUri());

      for (Map.Entry<String, List<String>> entry: pathParameters.entrySet()) {
        final String originalPathFragment = String.format("{%s}", entry.getKey());

        for (String currentPathFragment: entry.getValue()) {
          path = path.replace(currentPathFragment, originalPathFragment);
        }
      }
    }

    String name = annotation == null ? "" : annotation.name();

    if (name.length() == 0) {
      // we cannot use the class name as it is always a proxy
      name = resourceInfo.getResourceMethod().getName();
    }

    if (prefix != null && prefix.length() > 0) {
      name = prefix + "_" + name;
    }

    final String help = path.isEmpty() ? "Request for root" : path;

    tracker = CombinationDumpling.findOrCreate(name, help);
  }

  /**
   * Returns path of given URI. If the first character of path is '/' then it is removed.
   *
   * @author Pavol Loffay
   * @param uri to convert
   * @return path or null
   */
  public static String path(URI uri) {
    String path = uri.getPath();
    if (path != null && path.startsWith("/")) {
      path = path.substring(1);
    }

    return path;
  }

  @Override
  public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
	  LiteDumpling timer = (LiteDumpling) requestContext.getProperty(TRACKER_TIMER);

    if (timer != null) {
      timer.observe();
    }

    if (responseContext.getStatus() >= 500) {
      response_5xx.inc();
    } else if (responseContext.getStatus() >= 400) {
      response_4xx.inc();
    } else if (responseContext.getStatus() >= 300) {
      response_3xx.inc();
    } else if (responseContext.getStatus() >= 200) {
      response_2xx.inc();
    }
  }
}
