// k6 load test (task 6.4): 50 virtual users for 2 minutes against the API on the 1M-booking dataset.
// Each user is one of the generated customers. An iteration searches, opens a profile, checks free
// times, and one in five books the first free time.
//
// Run (the API must be up, with rate limits raised, because all traffic comes from one machine):
//   docker run --rm -v "$PWD/api/load-test:/scripts" -e BASE_URL=http://host.docker.internal:8080 \
//     grafana/k6:0.57.0 run /scripts/booking-journey.js
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const API = `${__ENV.BASE_URL || "http://localhost:8080"}/api/v1`;
const SERVICES = ["clean", "repair", "paint", "fan", "pipe", "wiring", "tutoring", "assembly"];

const bookingsCreated = new Counter("bookings_created");
const slotTaken = new Counter("bookings_slot_taken");

export const options = {
  scenarios: { journey: { executor: "constant-vus", vus: 50, duration: "2m" } },
  // A threshold per endpoint also makes k6 report each endpoint's latency separately.
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{name:search}": ["p(95)<500"],
    "http_req_duration{name:profile}": ["p(95)<500"],
    "http_req_duration{name:slots}": ["p(95)<500"],
    "http_req_duration{name:book}": ["p(95)<1000"],
  },
  summaryTrendStats: ["med", "p(95)", "p(99)", "max"],
};

// Two people booking the same time is normal; the database refuses the second with 409.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

let session = null;
let reported = 0;

// Prints the first few unexpected answers from each user, so failures can be explained, not just counted.
function report(res, name) {
  if (res.status >= 400 && res.status !== 409 && reported < 3) {
    reported++;
    console.warn(`${name} ${res.status} ${String(res.body).slice(0, 160)}`);
  }
}

function signIn() {
  const n = 1 + ((__VU * 997) % 50000);
  const res = http.post(
    `${API}/auth/login`,
    JSON.stringify({ email: `customer${n}@bench.sevak.test`, password: "Demo@1234" }),
    { headers: { "Content-Type": "application/json" }, tags: { name: "login" } },
  );
  check(res, { "logged in": (r) => r.status === 200 });
  const headers = { Authorization: `Bearer ${res.json("accessToken")}`, "Content-Type": "application/json" };
  const addresses = http.get(`${API}/me/addresses`, { headers, tags: { name: "addresses" } }).json();
  return { headers, home: addresses[0] };
}

function indiaToday() {
  return new Date(Date.now() + 5.5 * 3600 * 1000).toISOString().slice(0, 10);
}

export default function () {
  if (!session) session = signIn();
  const { headers, home } = session;

  const service = SERVICES[Math.floor(Math.random() * SERVICES.length)];
  const search = http.get(`${API}/providers?service=${service}&city=${home.cityName}&limit=10`, {
    tags: { name: "search" },
  });
  check(search, { "search ok": (r) => r.status === 200 });
  const offers = search.json("data") || [];
  if (offers.length === 0) return;
  const offer = offers[Math.floor(Math.random() * offers.length)];

  const profile = http.get(`${API}/providers/${offer.providerId}`, { tags: { name: "profile" } });
  check(profile, { "profile ok": (r) => r.status === 200 });

  const slots = http.get(
    `${API}/providers/${offer.providerId}/slots?from=${indiaToday()}&days=14&minutes=60`,
    { tags: { name: "slots" } },
  );
  check(slots, { "slots ok": (r) => r.status === 200 });
  report(slots, "slots");
  const free = slots.json() || [];

  if (free.length === 0 || Math.random() >= 0.2) return;
  const res = http.post(
    `${API}/bookings`,
    JSON.stringify({
      providerId: offer.providerId,
      locationId: home.locationId,
      scheduledStart: free[0].start,
      items: [{ serviceId: offer.serviceId, quantity: 1 }],
    }),
    { headers: { ...headers, "Idempotency-Key": `k6-${__VU}-${__ITER}` }, tags: { name: "book" } },
  );
  check(res, { "booked or slot taken": (r) => r.status === 201 || r.status === 409 });
  report(res, "book");
  if (res.status === 201) bookingsCreated.add(1);
  if (res.status === 409) slotTaken.add(1);
}
