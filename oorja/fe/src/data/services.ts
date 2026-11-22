export type CleaningTier = { id: string; label: string; description: string; price: string };

export type Service = {
  id: string;
  category: "installation" | "maintenance" | "cleaning";
  title: string;
  shortTitle: string;
  description: string;
  cta: string;
  price: string;
  priceValue: number;
  icon: string;
  accent: string;
  bullets: string[];
  steps: string[];
  cleaningTiers?: CleaningTier[];
};

export const services: Service[] = [
  {
    id: "installation",
    category: "installation",
    title: "New Solar Installation",
    shortTitle: "New Installation",
    description: "Complete solar installation for residential, commercial and industrial roofs, designed and commissioned end to end.",
    cta: "Get a Quote",
    price: "₹60,000",
    priceValue: 60000,
    icon: "solar",
    accent: "mint",
    bullets: [
      "New rooftop solar installation",
      "Ground-mounted installation",
      "Site survey & load assessment",
      "Panel & inverter selection",
      "Structure design & mounting",
      "Wiring & electrical work",
      "Net-metering assistance",
      "Commissioning & handover"
    ],
    steps: ["Select service", "Site details", "Capacity / requirement", "Upload photos", "Site survey", "Quotation", "Approval", "Installation", "Commissioning"]
  },
  {
    id: "expansion",
    category: "installation",
    title: "Plant Expansion",
    shortTitle: "Plant Expansion",
    description: "Increase capacity and expand your existing solar plant efficiently, with minimal downtime to current output.",
    cta: "Get a Quote",
    price: "₹50,000",
    priceValue: 50000,
    icon: "solar",
    accent: "mint",
    bullets: [
      "Capacity increase / panel additions",
      "Inverter upgrade for added load",
      "Structure & wiring extension",
      "Compatibility check with existing plant",
      "Updated net-metering application",
      "Performance testing after expansion"
    ],
    steps: ["Select service", "Existing plant details", "Expansion requirement", "Site survey", "Quotation", "Approval", "Installation", "Commissioning"]
  },
  {
    id: "maintenance",
    category: "maintenance",
    title: "Preventive Maintenance",
    shortTitle: "Preventive Maintenance",
    description: "Regular maintenance to improve performance and increase the lifespan of your plant with scheduled technician visits.",
    cta: "Request Service",
    price: "₹3,500",
    priceValue: 3500,
    icon: "wrench",
    accent: "blue",
    bullets: [
      "Full plant health checkup",
      "Panel & wiring inspection",
      "Inverter diagnostics",
      "Earthing & connector checks",
      "Performance benchmarking",
      "Detailed maintenance report"
    ],
    steps: ["Select plant", "Choose preferred date", "Service request", "Technician assigned", "Maintenance visit", "Service report"]
  },
  {
    id: "breakdown",
    category: "maintenance",
    title: "Breakdown Repair",
    shortTitle: "Breakdown Repair",
    description: "Quick repair for inverters, electrical faults and other issues so your plant is back to full output fast.",
    cta: "Request Service",
    price: "₹3,500",
    priceValue: 3500,
    icon: "wrench",
    accent: "blue",
    bullets: [
      "Inverter fault diagnosis",
      "Electrical fault repair",
      "Panel-related fault repair",
      "Emergency same-week visits",
      "Spare part replacement",
      "Post-repair performance check"
    ],
    steps: ["Select plant", "Describe the issue", "Upload photos", "Choose preferred date", "Technician assigned", "Diagnosis & repair", "Service report"]
  },
  {
    id: "cleaning",
    category: "cleaning",
    title: "Solar Plant Cleaning",
    shortTitle: "Plant Cleaning",
    description: "Professional cleaning for maximum output and better efficiency, for residential rooftops and commercial plants alike.",
    cta: "Book Cleaning",
    price: "₹2,500",
    priceValue: 2500,
    icon: "clean",
    accent: "yellow",
    bullets: [
      "Improves energy output",
      "Extends panel life",
      "Trained & verified professionals",
      "Eco-friendly cleaning process",
      "Before / after photo report",
      "Available for all plant sizes"
    ],
    steps: ["Select plant", "Select cleaning type", "Select date", "Confirm", "Payment", "Technician assigned", "Cleaning", "Before/after photos", "Completion report"],
    cleaningTiers: [
      { id: "regular", label: "Regular Cleaning", description: "Dry brush cleaning for light dust build-up", price: "₹2,500" },
      { id: "deep", label: "Deep Cleaning", description: "Water-assisted deep clean for heavy soiling", price: "₹3,500" }
    ]
  },
  {
    id: "inspection",
    category: "maintenance",
    title: "Inspection & Checkup",
    shortTitle: "Inspection & Checkup",
    description: "Detailed inspection with a performance report and recommendations to keep your plant running at its best.",
    cta: "Request Service",
    price: "₹2,000",
    priceValue: 2000,
    icon: "wrench",
    accent: "blue",
    bullets: [
      "Full visual & electrical inspection",
      "Thermal / hotspot check",
      "Output vs. expected performance",
      "Soiling & shading assessment",
      "Written recommendations",
      "Digital inspection report"
    ],
    steps: ["Select plant", "Choose preferred date", "Service request", "Technician assigned", "Inspection", "Report & recommendations"]
  }
];

export const categories = [
  { id: "all", label: "All Services" },
  { id: "installation", label: "Installation" },
  { id: "maintenance", label: "Maintenance" },
  { id: "cleaning", label: "Cleaning" }
] as const;
